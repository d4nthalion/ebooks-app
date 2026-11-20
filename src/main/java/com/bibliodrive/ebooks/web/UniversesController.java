package com.bibliodrive.ebooks.web;

import com.bibliodrive.ebooks.universes.SagaOrder;
import com.bibliodrive.ebooks.universes.Universes;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/** Universos: los ve todo el mundo; los cambian los administradores. */
@RestController
@RequestMapping("/api")
public class UniversesController {

    /** Posiciones de los libros sin numero de una saga: id del libro → posicion decimal. */
    public record OrderChange(String saga, Map<String, Double> positions) {
    }

    private final Universes universes;
    private final SagaOrder sagaOrder;

    public UniversesController(Universes universes, SagaOrder sagaOrder) {
        this.universes = universes;
        this.sagaOrder = sagaOrder;
    }

    @GetMapping("/universes")
    public List<Universes.Universe> list() {
        return universes.list();
    }

    @GetMapping("/saga-order")
    public Map<String, SagaOrder.Entry> sagaOrder() {
        return sagaOrder.all();
    }

    @PutMapping("/admin/saga-order")
    public Map<String, SagaOrder.Entry> setSagaOrder(Authentication auth, HttpServletRequest http,
            @RequestBody OrderChange body) {
        sagaOrder.set(body.saga(), body.positions() == null ? Map.of() : body.positions(), auth.getName(),
                http.getRemoteAddr());
        return sagaOrder.all();
    }

    @PostMapping("/admin/universes")
    public Universes.Universe create(Authentication auth, HttpServletRequest http, @RequestBody Universes.Change body) {
        return universes.create(body, auth.getName(), http.getRemoteAddr());
    }

    @PutMapping("/admin/universes/{id}")
    public Universes.Universe update(Authentication auth, HttpServletRequest http, @PathVariable String id,
            @RequestBody Universes.Change body) {
        return universes.update(id, body, auth.getName(), http.getRemoteAddr());
    }

    @DeleteMapping("/admin/universes/{id}")
    public ResponseEntity<Void> delete(Authentication auth, HttpServletRequest http, @PathVariable String id) {
        universes.delete(id, auth.getName(), http.getRemoteAddr());
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, String>> notFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Ese universo ya no existe"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
}
