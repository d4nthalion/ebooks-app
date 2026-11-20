package com.bibliodrive.ebooks.amule;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Cliente del API REST de aMule (amuleapi, {@code /api/v1}), el mismo aMule del
 * NAS que usa CineLocal. Traduce sus respuestas a lo que pinta la interfaz.
 */
public class AmuleClient {

    private static final Pattern HASH = Pattern.compile("^[0-9a-f]{32}$");
    private static final Pattern ED2K = Pattern.compile("^ed2k://\\|file\\|[^|]+\\|\\d+\\|[0-9a-fA-F]{32}\\|.*");

    /** Error con un codigo: offline, auth, unconfigured, not_found, error. */
    public static class AmuleException extends IOException {
        public final String code;

        public AmuleException(String message, String code) {
            super(message);
            this.code = code;
        }
    }

    public record Status(String server, String serverName, String kad, long down, long up, Long freeBytes) {
    }

    public record Download(String hash, String name, long size, long done, double progress, long speed,
            int sources, int sourcesTotal, String state) {
    }

    public record Transfers(Status status, List<Download> downloads) {
    }

    public record Result(String hash, String name, long size, int sources, int complete) {
    }

    public record Results(List<Result> results, boolean done, boolean expired) {
    }

    private static final Map<String, String> STATES = Map.of(
            "downloading", "downloading", "waiting", "waiting", "hashing", "hashing", "allocating", "waiting",
            "paused", "paused", "stopped", "stopped", "completing", "completing", "completed", "complete",
            "erroneous", "error", "insufficient_disk", "error");

    private final String url;
    private final String password;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper json = new ObjectMapper();
    private volatile String token;

    public AmuleClient(String url, String password) {
        this.url = url.replaceAll("/+$", "");
        this.password = password;
    }

    public boolean configured() {
        return !url.isBlank() && !password.isBlank();
    }

    // --- Estado y descargas ---------------------------------------------------------

    public Transfers transfers() throws IOException {
        JsonNode st = call("GET", "/status", null);
        if (!st.path("ec_connected").asBoolean(true)) {
            throw new AmuleException("aMule está arrancando o su núcleo no responde", "offline");
        }
        JsonNode dl = call("GET", "/downloads?status=all", null);
        List<Download> downloads = new ArrayList<>();
        for (JsonNode d : dl.path("downloads")) {
            String hash = d.path("hash").asText("").toLowerCase();
            if (!HASH.matcher(hash).matches()) continue;
            long size = d.path("size_bytes").asLong(0);
            String state = STATES.getOrDefault(d.path("status").asText(""), "waiting");
            int total = d.path("sources").path("total").asInt(0);
            if ("downloading".equals(state) && total == 0) state = "stalled";
            boolean complete = "complete".equals(state);
            double progress = complete ? 1 : Math.max(0, Math.min(1, d.path("progress").path("percent").asDouble(0) / 100));
            downloads.add(new Download(hash, d.path("name").asText(""), size,
                    complete ? size : d.path("completed_bytes").asLong(0), progress,
                    d.path("speed_bytes_per_second").asLong(0),
                    d.path("sources").path("transferring").asInt(0), total, state));
        }
        return new Transfers(status(st), downloads);
    }

    private static Status status(JsonNode s) {
        JsonNode ed = s.path("ed2k");
        JsonNode kad = s.path("kad");
        String server = "connected".equals(ed.path("state").asText()) ? (ed.path("high_id").asBoolean() ? "high" : "low")
                : "connecting".equals(ed.path("state").asText()) ? "connecting" : "off";
        String kadState = "connected".equals(kad.path("state").asText())
                ? (kad.path("firewalled_tcp").asBoolean() ? "firewalled" : "ok")
                : "connecting".equals(kad.path("state").asText()) ? "connecting" : "off";
        JsonNode free = s.path("disk").path("incoming_free_bytes");
        return new Status(server, "off".equals(server) ? "" : ed.path("server_name").asText(""), kadState,
                s.path("speeds").path("download_speed_bytes_per_second").asLong(0),
                s.path("speeds").path("upload_speed_bytes_per_second").asLong(0),
                free.isNumber() ? free.asLong() : null);
    }

    /** op: pause | resume | stop | cancel | clear */
    public void fileOp(String hash, String op) throws IOException {
        if (!HASH.matcher(hash).matches()) throw new AmuleException("Descarga no válida", "error");
        switch (op) {
            case "clear" -> call("POST", "/downloads_clear_completed", Map.of("hash", hash));
            case "cancel" -> call("DELETE", "/downloads/" + hash, null);
            case "pause", "resume", "stop" -> call("PATCH", "/downloads/" + hash, Map.of("action", op));
            default -> throw new AmuleException("Operación no válida", "error");
        }
    }

    public void addLink(String link) throws IOException {
        String l = link == null ? "" : link.trim();
        if (!ED2K.matcher(l).matches()) throw new AmuleException("Eso no parece un enlace ed2k de archivo", "error");
        JsonNode r = call("POST", "/downloads", Map.of("links", List.of(l)));
        for (JsonNode x : r.path("results")) {
            if (!x.path("ok").asBoolean(true)) {
                throw new AmuleException(x.path("error").path("message").asText("aMule no ha aceptado el enlace"), "error");
            }
        }
    }

    // --- Busqueda -------------------------------------------------------------------

    /** network: global (servidores) | kad. Devuelve el id de la busqueda. */
    public long search(String query, String network, Long previous) throws IOException {
        String q = query == null ? "" : query.trim();
        if (q.isEmpty()) throw new AmuleException("Escribe qué buscar", "error");
        if (previous != null) {
            try {
                call("DELETE", "/search/" + previous, null);
            } catch (IOException ignored) {
                // la anterior ya habia caducado
            }
        }
        JsonNode s = call("POST", "/search", Map.of("query", q, "type", "kad".equals(network) ? "kad" : "global"));
        return s.path("search_id").asLong();
    }

    public Results results(long id) throws IOException {
        JsonNode r;
        try {
            r = call("GET", "/search/" + id + "/results", null);
        } catch (AmuleException e) {
            if ("not_found".equals(e.code)) return new Results(List.of(), true, true);
            throw e;
        }
        List<Result> results = new ArrayList<>();
        for (JsonNode x : r.path("results")) {
            String hash = x.path("hash").asText("").toLowerCase();
            if (!HASH.matcher(hash).matches()) continue;
            results.add(new Result(hash, x.path("name").asText(""), x.path("size_bytes").asLong(0),
                    x.path("sources").path("total").asInt(0), x.path("sources").path("complete").asInt(0)));
        }
        return new Results(results, !"running".equals(r.path("progress").path("state").asText("")), false);
    }

    public void download(String hash) throws IOException {
        if (!HASH.matcher(hash).matches()) throw new AmuleException("Resultado no válido", "error");
        call("POST", "/search/results/" + hash + "/download", Map.of());
    }

    // --- Peticiones -----------------------------------------------------------------

    private synchronized void login() throws IOException {
        if (!configured()) throw new AmuleException("Falta configurar aMule (app.amule.password)", "unconfigured");
        token = null;
        JsonNode r = raw("POST", "/auth/login?include_token=true", Map.of("password", password), false);
        token = r.path("token").asText(null);
    }

    /**
     * Con sesion. El token dura 24 h y se pierde si amuleapi se reinicia: un 401
     * vuelve a entrar una sola vez (nunca en bucle: amuleapi bloquearia la IP).
     */
    private JsonNode call(String method, String path, Object body) throws IOException {
        if (token == null) login();
        try {
            return raw(method, path, body, true);
        } catch (AmuleException e) {
            if (!"auth".equals(e.code)) throw e;
            login();
            return raw(method, path, body, true);
        }
    }

    private JsonNode raw(String method, String path, Object body, boolean auth) throws IOException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url + "/api/v1" + path))
                .timeout(Duration.ofSeconds(15));
        if (auth && token != null) b.header("Authorization", "Bearer " + token);
        if (body != null) {
            b.header("Content-Type", "application/json");
            b.method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        } else {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        }
        HttpResponse<String> res;
        try {
            res = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AmuleException("Interrumpido", "offline");
        } catch (IOException e) {
            throw new AmuleException("aMule no responde", "offline");
        }
        JsonNode data = res.body() == null || res.body().isBlank() ? json.createObjectNode() : readOrEmpty(res.body());
        if (res.statusCode() >= 200 && res.statusCode() < 300) return data;
        String code = data.path("error").path("code").asText("");
        String message = data.path("error").path("message").asText("HTTP " + res.statusCode());
        if (res.statusCode() == 401) {
            throw new AmuleException(path.startsWith("/auth/") ? "aMule ha rechazado la contraseña" : message, "auth");
        }
        if (res.statusCode() == 429) {
            throw new AmuleException("aMule ha bloqueado los intentos un rato (demasiados fallos)", "auth");
        }
        if ("ec_unavailable".equals(code)) {
            throw new AmuleException("aMule está arrancando o su núcleo no responde", "offline");
        }
        throw new AmuleException(message, code.isEmpty() ? (res.statusCode() == 404 ? "not_found" : "error") : code);
    }

    private JsonNode readOrEmpty(String body) {
        try {
            return json.readTree(body);
        } catch (IOException e) {
            return json.createObjectNode();
        }
    }
}
