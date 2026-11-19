package com.bibliodrive.ebooks.edit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Busca portadas en Open Library (sin clave de API).
 */
@Service
public class CoverSearchService {

    private static final Logger log = LoggerFactory.getLogger(CoverSearchService.class);

    /** Tamano maximo de imagen aceptada (subida o descargada). */
    public static final long MAX_IMAGE_BYTES = 20L * 1024 * 1024;

    private static final String OPEN_LIBRARY = "https://openlibrary.org";
    private static final String COVERS = "https://covers.openlibrary.org/b/id/";
    private static final String USER_AGENT = "ebooks-api/1.0 (biblioteca personal)";
    private static final int MAX_WORKS = 4;
    private static final int MAX_CANDIDATES = 40;

    /** Una portada encontrada. {@code language} es el codigo de Open Library (spa, eng...) o null. */
    public record CoverCandidate(long coverId, String thumbnailUrl, String title, String publisher,
            String language) {
    }

    private record Ranked(CoverCandidate candidate, int rank) {
    }

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    // --- Busqueda -----------------------------------------------------------------

    /**
     * Busca las obras que encajan con {@code query} y devuelve las portadas de sus
     * ediciones, primero las que estan en espanol.
     */
    public List<CoverCandidate> search(String query) throws IOException, InterruptedException {
        String q = query == null ? "" : query.trim();
        if (q.isEmpty()) {
            return List.of();
        }

        JsonNode search = getJson(OPEN_LIBRARY + "/search.json?limit=" + MAX_WORKS
                + "&fields=key,title,cover_i&q=" + URLEncoder.encode(q, StandardCharsets.UTF_8));

        List<Ranked> ranked = new ArrayList<>();
        Set<Long> seen = new LinkedHashSet<>();
        int order = 0;
        for (JsonNode work : search.path("docs")) {
            String workTitle = work.path("title").asText(null);
            long mainCover = work.path("cover_i").asLong(0);
            if (mainCover > 0 && seen.add(mainCover)) {
                ranked.add(new Ranked(candidate(mainCover, workTitle, null, null), 2000 + order++));
            }

            String key = work.path("key").asText("");
            if (!key.startsWith("/works/")) {
                continue;
            }
            JsonNode editions;
            try {
                editions = getJson(OPEN_LIBRARY + key + "/editions.json?limit=100");
            } catch (IOException e) {
                log.warn("No se pudieron leer las ediciones de {}: {}", key, e.getMessage());
                continue;
            }
            for (JsonNode edition : editions.path("entries")) {
                String language = languageOf(edition);
                String publisher = edition.path("publishers").path(0).asText(null);
                String title = edition.path("title").asText(workTitle);
                for (JsonNode cover : edition.path("covers")) {
                    long id = cover.asLong(0);
                    if (id > 0 && seen.add(id)) {
                        int languageRank = "spa".equals(language) ? 0 : language == null ? 1000 : 3000;
                        ranked.add(new Ranked(candidate(id, title, publisher, language), languageRank + order++));
                    }
                }
            }
        }

        return ranked.stream()
                .sorted(Comparator.comparingInt(Ranked::rank))
                .limit(MAX_CANDIDATES)
                .map(Ranked::candidate)
                .toList();
    }

    private static CoverCandidate candidate(long id, String title, String publisher, String language) {
        return new CoverCandidate(id, COVERS + id + "-M.jpg", title, publisher, language);
    }

    private static String languageOf(JsonNode edition) {
        String key = edition.path("languages").path(0).path("key").asText(null);
        return key == null ? null : key.substring(key.lastIndexOf('/') + 1);
    }

    private JsonNode getJson(String url) throws IOException, InterruptedException {
        HttpResponse<InputStream> res = send(url);
        try (InputStream body = res.body()) {
            if (res.statusCode() != 200) {
                throw new IOException("Open Library respondio " + res.statusCode());
            }
            return json.readTree(body);
        }
    }

    private HttpResponse<InputStream> send(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofInputStream());
    }

    // --- Descarga -----------------------------------------------------------------

    /** Descarga la portada grande de Open Library. El id lo construimos nosotros: no se aceptan URLs. */
    public byte[] downloadOpenLibraryCover(long coverId) throws IOException, InterruptedException {
        if (coverId <= 0) {
            throw new IllegalArgumentException("Id de portada no valido");
        }
        HttpResponse<InputStream> res = send(COVERS + coverId + "-L.jpg?default=false");
        try (InputStream body = res.body()) {
            if (res.statusCode() != 200) {
                throw new IOException("Open Library no tiene esa portada (" + res.statusCode() + ")");
            }
            byte[] data = body.readNBytes((int) MAX_IMAGE_BYTES + 1);
            if (data.length > MAX_IMAGE_BYTES) {
                throw new IllegalArgumentException("La imagen es demasiado grande");
            }
            return data;
        }
    }
}
