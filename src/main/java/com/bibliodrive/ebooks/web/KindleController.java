package com.bibliodrive.ebooks.web;

import com.bibliodrive.ebooks.catalog.Book;
import com.bibliodrive.ebooks.catalog.CatalogIndexService;
import com.bibliodrive.ebooks.kindle.KindleMailService;
import com.bibliodrive.ebooks.user.UserPreferencesService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class KindleController {

    private static final Logger log = LoggerFactory.getLogger(KindleController.class);

    private final UserPreferencesService preferences;
    private final KindleMailService mailService;
    private final CatalogIndexService catalog;

    public KindleController(UserPreferencesService preferences, KindleMailService mailService,
            CatalogIndexService catalog) {
        this.preferences = preferences;
        this.mailService = mailService;
        this.catalog = catalog;
    }

    @GetMapping("/me/kindle-email")
    public Map<String, String> getKindleEmail(Authentication auth) {
        String email = preferences.getKindleEmail(auth.getName());
        return Map.of("email", email == null ? "" : email);
    }

    @PutMapping("/me/kindle-email")
    public Map<String, String> setKindleEmail(Authentication auth, @RequestBody Map<String, String> body) {
        String email = body.getOrDefault("email", "").trim();
        preferences.setKindleEmail(auth.getName(), email);
        return Map.of("email", email);
    }

    @PostMapping("/books/{id}/send-to-kindle")
    public ResponseEntity<Map<String, String>> sendToKindle(Authentication auth, @PathVariable String id) {
        String kindleEmail = preferences.getKindleEmail(auth.getName());
        if (kindleEmail == null || kindleEmail.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "No has configurado tu email de Kindle todavia"));
        }

        return catalog.findById(id).<ResponseEntity<Map<String, String>>>map(book -> {
            Path file = catalog.resolveFile(book);
            if (!Files.exists(file)) {
                return ResponseEntity.notFound().build();
            }
            try {
                mailService.sendToKindle(kindleEmail, book, file.toFile());
                return ResponseEntity.ok(Map.of("status", "enviado a " + kindleEmail));
            } catch (Exception e) {
                log.error("Error enviando {} a Kindle ({})", book.fileName(), kindleEmail, e);
                return ResponseEntity.internalServerError().body(Map.of("error", "No se pudo enviar: " + e.getMessage()));
            }
        }).orElseGet(() -> ResponseEntity.notFound().build());
    }
}
