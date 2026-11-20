package com.bibliodrive.ebooks.web;

import com.bibliodrive.ebooks.amule.AmuleService;
import com.bibliodrive.ebooks.catalog.Book;
import com.bibliodrive.ebooks.requests.BookRequests;
import com.bibliodrive.ebooks.requests.OpenLibraryBooks;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/** Peticiones de libros: buscar, pedir, retirar y (administradores) resolver. */
@RestController
@RequestMapping("/api")
public class RequestsController {

    public record Resolve(String status, String reply) {
    }

    private final BookRequests requests;
    private final OpenLibraryBooks openLibrary;
    private final AmuleService amule;

    public RequestsController(BookRequests requests, OpenLibraryBooks openLibrary, AmuleService amule) {
        this.requests = requests;
        this.openLibrary = openLibrary;
        this.amule = amule;
    }

    /** Las mias; un administrador con {@code all=true}, las de todos. Al verlas, dejan de ser novedad. */
    @GetMapping("/requests")
    public List<Map<String, Object>> list(Authentication auth, @RequestParam(defaultValue = "false") boolean all) {
        boolean everyone = all && MeController.isAdmin(auth);
        Map<String, Double> progress = amule.progressByRequest();
        String me = auth.getName().toLowerCase(java.util.Locale.ROOT);
        List<Map<String, Object>> result = requests.list(auth.getName(), everyone).stream()
                .map(r -> view(r, me, everyone, progress.get(r.id)))
                .toList();
        if (!all) requests.markSeen(auth.getName());
        return result;
    }

    /** Buscar libros para pedir, marcando los que ya estan en la biblioteca o ya se han pedido. */
    @GetMapping("/requests/lookup")
    public List<Map<String, Object>> lookup(Authentication auth, @RequestParam String q)
            throws IOException, InterruptedException {
        String me = auth.getName().toLowerCase(java.util.Locale.ROOT);
        List<BookRequests.Request> mine = requests.list(auth.getName(), false);
        List<BookRequests.Request> everyone = requests.list(auth.getName(), true);
        return openLibrary.search(q).stream().map(f -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("olKey", f.olKey());
            m.put("title", f.title());
            m.put("originalTitle", f.originalTitle());
            m.put("author", f.author());
            m.put("year", f.year());
            m.put("coverId", f.coverId());
            m.put("publisher", f.publisher());
            m.put("spanish", f.spanish());
            m.put("editions", f.editions());
            m.put("bookId", requests.inLibrary(f.title(), f.author()).map(Book::id)
                    .or(() -> f.originalTitle() == null ? java.util.Optional.empty()
                            : requests.inLibrary(f.originalTitle(), f.author()).map(Book::id))
                    .orElse(null));
            BookRequests.Request existing = everyone.stream()
                    .filter(r -> f.olKey().equals(r.olKey) && BookRequests.PENDING.equals(r.status))
                    .findFirst().orElse(null);
            m.put("requested", existing == null ? null : mine.contains(existing) ? "me" : "others");
            return m;
        }).toList();
    }

    @PostMapping("/requests")
    public Map<String, Object> create(Authentication auth, HttpServletRequest http,
            @RequestBody BookRequests.NewRequest body) {
        BookRequests.Request r = requests.create(auth.getName(), body, http.getRemoteAddr());
        return view(r, auth.getName().toLowerCase(java.util.Locale.ROOT), false, null);
    }

    @DeleteMapping("/requests/{id}")
    public ResponseEntity<Void> cancel(Authentication auth, HttpServletRequest http, @PathVariable String id) {
        requests.cancel(auth.getName(), id, http.getRemoteAddr());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/admin/requests/{id}")
    public Map<String, Object> resolve(Authentication auth, HttpServletRequest http, @PathVariable String id,
            @RequestBody Resolve body) {
        BookRequests.Request r = requests.resolve(auth.getName(), id, body.status(), body.reply(), http.getRemoteAddr());
        return view(r, auth.getName().toLowerCase(java.util.Locale.ROOT), true, amule.progressByRequest().get(r.id));
    }

    /** Lo que ve cada uno: quien mas lo ha pedido solo lo ven los administradores. */
    private static Map<String, Object> view(BookRequests.Request r, String me, boolean admin, Double progress) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.id);
        m.put("olKey", r.olKey);
        m.put("title", r.title);
        m.put("author", r.author);
        m.put("year", r.year);
        m.put("coverId", r.coverId);
        m.put("series", r.series);
        m.put("seriesIndex", r.seriesIndex);
        m.put("status", r.status);
        m.put("at", r.at);
        m.put("resolvedAt", r.resolvedAt);
        m.put("reply", r.reply);
        m.put("bookId", r.bookId);
        m.put("downloading", progress);
        m.put("mine", r.users.stream().anyMatch(u -> u.name.equals(me)));
        m.put("count", r.users.size());
        if (admin) {
            m.put("resolvedBy", r.resolvedBy);
            m.put("users", r.users.stream().map(u -> {
                Map<String, Object> x = new LinkedHashMap<>();
                x.put("name", u.name);
                x.put("at", u.at);
                x.put("note", u.note);
                return x;
            }).toList());
        } else {
            r.users.stream().filter(u -> u.name.equals(me)).findFirst().ifPresent(u -> m.put("note", u.note));
        }
        return m;
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, String>> notFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Petición no encontrada"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler({IOException.class, InterruptedException.class})
    public ResponseEntity<Map<String, String>> openLibraryDown(Exception e) {
        if (e instanceof InterruptedException) Thread.currentThread().interrupt();
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(Map.of("error", "Open Library no responde ahora mismo. Prueba en un rato."));
    }
}
