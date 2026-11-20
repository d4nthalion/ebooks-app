package com.bibliodrive.ebooks.web;

import com.bibliodrive.ebooks.catalog.Book;
import com.bibliodrive.ebooks.catalog.CatalogIndexService;
import com.bibliodrive.ebooks.log.AccessLog;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.Authentication;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class BookController {

    private final CatalogIndexService catalog;
    private final AccessLog accessLog;

    public BookController(CatalogIndexService catalog, AccessLog accessLog) {
        this.catalog = catalog;
        this.accessLog = accessLog;
    }

    @GetMapping("/books")
    public List<Book> books(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String author,
            @RequestParam(required = false) String series) {
        return catalog.findAll(search, author, series);
    }

    @GetMapping("/books/{id}")
    public ResponseEntity<Book> book(@PathVariable String id) {
        return catalog.findById(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/books/{id}/cover")
    public ResponseEntity<byte[]> cover(@PathVariable String id) {
        return catalog.coverOf(id)
                .map(cover -> ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(cover.contentType()))
                        .body(cover.data()))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/books/{id}/download")
    public ResponseEntity<FileSystemResource> download(@PathVariable String id, Authentication auth,
            HttpServletRequest http) {
        return catalog.findById(id).<ResponseEntity<FileSystemResource>>map(book -> {
            Path file = catalog.resolveFile(book);
            if (!Files.exists(file)) {
                return ResponseEntity.notFound().build();
            }
            accessLog.event(AccessLog.Kind.DOWNLOAD, auth.getName(), http.getRemoteAddr(),
                    book.title() + (book.author() == null ? "" : " · " + book.author()),
                    book.extension().toUpperCase(java.util.Locale.ROOT) + ", " + (book.sizeBytes() / 1024) + " KB");
            String encodedName = URLEncoder.encode(book.fileName(), StandardCharsets.UTF_8).replace("+", "%20");
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encodedName)
                    .body(new FileSystemResource(file));
        }).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/authors")
    public List<String> authors() {
        return catalog.authors();
    }

    @GetMapping("/series")
    public List<String> series(@RequestParam(required = false) String author) {
        return catalog.seriesList(author);
    }

    @GetMapping("/index/status")
    public Map<String, Object> status() {
        return Map.of("libros", catalog.size(), "actualizado", catalog.lastIndexedAt());
    }

    @PostMapping("/index/refresh")
    public Map<String, Object> refresh(Authentication auth, HttpServletRequest http) {
        catalog.refresh();
        accessLog.event(AccessLog.Kind.REFRESH, auth.getName(), http.getRemoteAddr(),
                "Biblioteca releída: " + catalog.size() + " libros");
        return status();
    }
}
