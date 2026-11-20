package com.bibliodrive.ebooks.web;

import com.bibliodrive.ebooks.amule.AmuleClient;
import com.bibliodrive.ebooks.amule.AmuleService;
import com.bibliodrive.ebooks.amule.LibraryPlacer;
import com.bibliodrive.ebooks.log.AccessLog;
import com.bibliodrive.ebooks.security.LoginAttemptService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Panel de administracion: registro, bloqueos de login y descargas con aMule. Solo ROLE_ADMIN (SecurityConfig). */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    public record Search(String q, String network) {
    }

    public record DownloadRequest(String hash, String link, String requestId, String title, String author,
            String series, String seriesIndex) {
    }

    public record PlaceRequest(String title, String author, String series, String seriesIndex) {
    }

    public record Unblock(String key) {
    }

    private final AccessLog accessLog;
    private final LoginAttemptService loginAttempts;
    private final AmuleService amule;

    public AdminController(AccessLog accessLog, LoginAttemptService loginAttempts, AmuleService amule) {
        this.accessLog = accessLog;
        this.loginAttempts = loginAttempts;
        this.amule = amule;
    }

    // --- Registro -------------------------------------------------------------------

    @GetMapping("/log")
    public Map<String, Object> log(@RequestParam(required = false) String month,
            @RequestParam(required = false) String groups, @RequestParam(required = false) String user,
            @RequestParam(required = false) String q) {
        YearMonth m;
        try {
            m = month == null || month.isBlank() ? YearMonth.now() : YearMonth.parse(month);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Mes no válido");
        }
        Set<String> wanted = groups == null || groups.isBlank() ? Set.of()
                : Arrays.stream(groups.split(",")).map(String::trim).collect(Collectors.toSet());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("month", m.toString());
        result.put("months", accessLog.months());
        result.put("entries", accessLog.query(m, wanted, user, q).stream().map(e -> {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("at", e.at());
            x.put("kind", e.kind());
            x.put("group", AccessLog.groupOf(e.kind()));
            x.put("user", e.user());
            x.put("ip", e.ip());
            x.put("title", e.title());
            x.put("detail", e.detail());
            return x;
        }).toList());
        result.put("today", accessLog.todayCounts());
        return result;
    }

    @GetMapping("/login-blocks")
    public List<LoginAttemptService.Block> blocks() {
        return loginAttempts.blocks();
    }

    @PostMapping("/login-blocks/unblock")
    public Map<String, Object> unblock(Authentication auth, HttpServletRequest http, @RequestBody Unblock body) {
        boolean done = body.key() != null && loginAttempts.unblock(body.key());
        if (done) {
            accessLog.event(AccessLog.Kind.BLOCKED, auth.getName(), http.getRemoteAddr(), "Desbloqueado a mano: " + body.key());
        }
        return Map.of("unblocked", done);
    }

    // --- Descargas --------------------------------------------------------------------

    @GetMapping("/downloads")
    public AmuleService.Snapshot downloads() {
        return amule.snapshot();
    }

    @PostMapping("/downloads/search")
    public Map<String, Object> search(Authentication auth, @RequestBody Search body) throws IOException {
        requireAmule();
        return Map.of("searchId", amule.search(auth.getName(), body.q(), body.network()));
    }

    @GetMapping("/downloads/search")
    public AmuleClient.Results results(Authentication auth) throws IOException {
        requireAmule();
        return amule.results(auth.getName());
    }

    @PostMapping("/downloads")
    public Map<String, Object> download(Authentication auth, HttpServletRequest http, @RequestBody DownloadRequest body)
            throws IOException {
        requireAmule();
        AmuleService.Plan plan = new AmuleService.Plan();
        plan.requestId = blank(body.requestId());
        plan.title = blank(body.title());
        plan.author = blank(body.author());
        plan.series = blank(body.series());
        plan.seriesIndex = blank(body.seriesIndex());
        amule.download(auth.getName(), blank(body.hash()), blank(body.link()), plan, http.getRemoteAddr());
        return Map.of("ok", true);
    }

    @PostMapping("/downloads/{hash}/{op}")
    public Map<String, Object> fileOp(Authentication auth, HttpServletRequest http, @PathVariable String hash,
            @PathVariable String op) throws IOException {
        requireAmule();
        amule.fileOp(auth.getName(), hash, op, http.getRemoteAddr());
        return Map.of("ok", true);
    }

    @PostMapping("/downloads/pending/{hash}")
    public AmuleService.PlacedItem place(Authentication auth, HttpServletRequest http, @PathVariable String hash,
            @RequestBody PlaceRequest body) throws IOException {
        return amule.placePending(auth.getName(), hash,
                new LibraryPlacer.Info(blank(body.title()), blank(body.author()), blank(body.series()),
                        blank(body.seriesIndex())), http.getRemoteAddr());
    }

    @DeleteMapping("/downloads/pending/{hash}")
    public ResponseEntity<Void> dismiss(Authentication auth, HttpServletRequest http, @PathVariable String hash) {
        amule.dismissPending(auth.getName(), hash, http.getRemoteAddr());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/downloads/placed")
    public ResponseEntity<Void> clearPlaced() {
        amule.clearPlaced();
        return ResponseEntity.noContent().build();
    }

    private void requireAmule() {
        if (!amule.enabled()) throw new IllegalStateException("aMule no está configurado en BiblioLocal");
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    // --- Errores ----------------------------------------------------------------------

    @ExceptionHandler(AmuleClient.AmuleException.class)
    public ResponseEntity<Map<String, String>> amuleError(AmuleClient.AmuleException e) {
        HttpStatus status = "offline".equals(e.code) || "auth".equals(e.code) ? HttpStatus.SERVICE_UNAVAILABLE
                : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status).body(Map.of("error", e.getMessage(), "code", e.code));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> unavailable(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IOException.class)
    public ResponseEntity<Map<String, String>> ioError(IOException e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", e.getMessage()));
    }
}
