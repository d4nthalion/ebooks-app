package com.bibliodrive.ebooks.log;

import com.bibliodrive.ebooks.store.JsonStore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Registro de actividad para los administradores: quien entra, que descarga, que
 * manda al Kindle, que cambia, que pide, que hace aMule y los errores. Una linea
 * JSON por evento en {@code data/logs/access-AAAA-MM.jsonl}; se guardan
 * {@code app.log-months} meses.
 */
@Service
public class AccessLog {

    private static final Logger log = LoggerFactory.getLogger(AccessLog.class);
    private static final int MAX_RESULTS = 500;

    /** Tipos de evento. El grupo es lo que filtra la interfaz (Sesiones, Descargas...). */
    public enum Kind {
        LOGIN("session"), LOGIN_FAILED("session"), LOGIN_DENIED("session"), LOGOUT("session"), BLOCKED("session"),
        DOWNLOAD("download"), KINDLE("kindle"),
        EDIT("edit"), COVER("edit"), RENAME("edit"), UNIVERSE("edit"), REFRESH("edit"),
        REQUEST("request"), REQUEST_RESOLVED("request"),
        AMULE("amule"),
        ERROR("error");

        public final String group;

        Kind(String group) {
            this.group = group;
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Entry(long at, String kind, String user, String ip, String title, String detail) {
    }

    private final Path dir;
    private final int months;
    private final ObjectMapper mapper = JsonStore.mapper();

    public AccessLog(@Value("${app.data-dir:data}") String dataDir, @Value("${app.log-months:12}") int months) {
        this.dir = Path.of(dataDir, "logs");
        this.months = months;
        prune();
    }

    /** Apunta un evento. Nunca lanza: que falle el registro no puede romper lo que se registra. */
    public void event(Kind kind, String user, String ip, String title, String detail) {
        Entry entry = new Entry(System.currentTimeMillis(), kind.name().toLowerCase(Locale.ROOT), user, ip,
                cut(title, 300), cut(detail, 500));
        try {
            Files.createDirectories(dir);
            String line = mapper.writeValueAsString(entry) + "\n";
            synchronized (this) {
                Files.writeString(fileOf(YearMonth.now()), line, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
        } catch (IOException e) {
            log.warn("Registro de actividad: {}", e.getMessage());
        }
    }

    public void event(Kind kind, String user, String ip, String title) {
        event(kind, user, ip, title, null);
    }

    /**
     * Eventos de un mes, los mas nuevos primero. {@code groups}: los grupos que se
     * quieren (vacio = todos); {@code q}: texto en el titulo, el detalle o la IP.
     */
    public List<Entry> query(YearMonth month, Set<String> groups, String user, String q) {
        Path file = fileOf(month);
        if (!Files.exists(file)) {
            return List.of();
        }
        String userKey = blankToNull(user) == null ? null : user.trim().toLowerCase(Locale.ROOT);
        String text = blankToNull(q) == null ? null : q.trim().toLowerCase(Locale.ROOT);
        List<Entry> found = new ArrayList<>();
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                Entry e;
                try {
                    e = mapper.readValue(line, Entry.class);
                } catch (IOException bad) {
                    continue;
                }
                if (!groups.isEmpty() && !groups.contains(groupOf(e.kind()))) continue;
                if (userKey != null && (e.user() == null || !e.user().toLowerCase(Locale.ROOT).equals(userKey))) continue;
                if (text != null && !matches(e, text)) continue;
                found.add(e);
            }
        } catch (IOException e) {
            log.warn("No se pudo leer {}: {}", file, e.getMessage());
        }
        Collections.reverse(found);
        return found.size() > MAX_RESULTS ? found.subList(0, MAX_RESULTS) : found;
    }

    /** Recuento de hoy por grupo, para el resumen del panel. */
    public java.util.Map<String, Long> todayCounts() {
        long start = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        java.util.Map<String, Long> counts = new java.util.HashMap<>();
        java.util.Set<String> users = new java.util.HashSet<>();
        for (Entry e : query(YearMonth.now(), Set.of(), null, null)) {
            if (e.at() < start) break;
            counts.merge(e.kind(), 1L, Long::sum);
            if (e.user() != null && "login".equals(e.kind())) users.add(e.user().toLowerCase(Locale.ROOT));
        }
        counts.put("people", (long) users.size());
        return counts;
    }

    /** Meses con registro, el mas reciente primero. */
    public List<String> months() {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(p -> p.getFileName().toString())
                    .filter(n -> n.matches("access-\\d{4}-\\d{2}\\.jsonl"))
                    .map(n -> n.substring(7, 14))
                    .sorted(Collections.reverseOrder())
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    public static String groupOf(String kind) {
        try {
            return Kind.valueOf(kind.toUpperCase(Locale.ROOT)).group;
        } catch (IllegalArgumentException e) {
            return "action";
        }
    }

    private static boolean matches(Entry e, String text) {
        return Stream.of(e.title(), e.detail(), e.ip())
                .anyMatch(v -> v != null && v.toLowerCase(Locale.ROOT).contains(text));
    }

    private Path fileOf(YearMonth month) {
        return dir.resolve("access-" + month + ".jsonl");
    }

    private void prune() {
        YearMonth oldest = YearMonth.now().minusMonths(months);
        for (String m : months()) {
            if (YearMonth.parse(m).isBefore(oldest)) {
                try {
                    Files.deleteIfExists(fileOf(YearMonth.parse(m)));
                } catch (IOException ignored) {
                    // se reintenta al siguiente arranque
                }
            }
        }
    }

    private static String cut(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
