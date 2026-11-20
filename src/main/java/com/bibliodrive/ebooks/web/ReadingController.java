package com.bibliodrive.ebooks.web;

import com.bibliodrive.ebooks.catalog.Book;
import com.bibliodrive.ebooks.catalog.CatalogIndexService;
import com.bibliodrive.ebooks.log.AccessLog;
import com.bibliodrive.ebooks.reading.Reading;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.NoSuchElementException;

/** Leer en la web: el epub para el lector, por donde va cada uno y lo que ha terminado. */
@RestController
@RequestMapping("/api")
public class ReadingController {

    public record Progress(String cfi, Double percent) {
    }

    public record ReadMark(boolean read) {
    }

    private final Reading reading;
    private final CatalogIndexService catalog;
    private final AccessLog accessLog;

    public ReadingController(Reading reading, CatalogIndexService catalog, AccessLog accessLog) {
        this.reading = reading;
        this.catalog = catalog;
        this.accessLog = accessLog;
    }

    @GetMapping("/me/reading")
    public Map<String, Reading.Entry> mine(Authentication auth) {
        return reading.of(auth.getName());
    }

    /** El epub para el lector de la web (no cuenta como descarga). */
    @GetMapping("/books/{id}/epub")
    public ResponseEntity<FileSystemResource> epub(@PathVariable String id, Authentication auth, HttpServletRequest http) {
        Book book = catalog.findById(id).orElseThrow(NoSuchElementException::new);
        if (!"epub".equals(book.extension())) throw new IllegalArgumentException("Solo se pueden leer en la web los epub");
        Path file = catalog.resolveFile(book);
        if (!Files.exists(file)) throw new NoSuchElementException();
        accessLog.event(AccessLog.Kind.READ, auth.getName(), http.getRemoteAddr(),
                book.title() + (book.author() == null ? "" : " · " + book.author()), "abierto en el lector");
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/epub+zip"))
                .cacheControl(CacheControl.noCache().cachePrivate())
                .body(new FileSystemResource(file));
    }

    @PutMapping("/books/{id}/progress")
    public Reading.Entry progress(@PathVariable String id, Authentication auth, @RequestBody Progress body) {
        catalog.findById(id).orElseThrow(NoSuchElementException::new);
        return reading.progress(auth.getName(), id, body.cfi(), body.percent() == null ? 0 : body.percent());
    }

    @PutMapping("/books/{id}/read")
    public Map<String, Reading.Entry> markRead(@PathVariable String id, Authentication auth, HttpServletRequest http,
            @RequestBody ReadMark body) {
        Book book = catalog.findById(id).orElseThrow(NoSuchElementException::new);
        if (reading.setRead(auth.getName(), id, body.read())) {
            accessLog.event(AccessLog.Kind.READ, auth.getName(), http.getRemoteAddr(),
                    (body.read() ? "Marcado como leído: " : "Desmarcado como leído: ") + book.title());
        }
        return reading.of(auth.getName());
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, String>> notFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Libro no encontrado"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
}
