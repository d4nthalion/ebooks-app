package com.bibliodrive.ebooks.web;

import com.bibliodrive.ebooks.catalog.Book;
import com.bibliodrive.ebooks.catalog.EpubMetadataWriter;
import com.bibliodrive.ebooks.edit.BookEditService;
import com.bibliodrive.ebooks.edit.CoverSearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/** Edicion de libros: portada (subida o de Open Library), metadatos y renombrados en bloque. */
@RestController
@RequestMapping("/api")
public class BookEditController {

    private static final Logger log = LoggerFactory.getLogger(BookEditController.class);

    /** Campos editables de un libro. null = no cambiar; saga vacia = quitarla. */
    public record MetadataRequest(String title, String author, String series, String seriesIndex) {
    }

    public record RenameRequest(String from, String to, String author) {
    }

    private final BookEditService edits;
    private final CoverSearchService coverSearch;

    public BookEditController(BookEditService edits, CoverSearchService coverSearch) {
        this.edits = edits;
        this.coverSearch = coverSearch;
    }

    // --- Portada ----------------------------------------------------------------

    @GetMapping("/covers/search")
    public List<CoverSearchService.CoverCandidate> searchCovers(@RequestParam String q)
            throws IOException, InterruptedException {
        return coverSearch.search(q);
    }

    @PostMapping("/books/{id}/cover")
    public Book uploadCover(@PathVariable String id, @RequestParam("file") MultipartFile file) throws IOException {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("No se ha recibido ninguna imagen");
        }
        if (file.getSize() > CoverSearchService.MAX_IMAGE_BYTES) {
            throw new IllegalArgumentException("La imagen es demasiado grande (maximo 20 MB)");
        }
        return edits.applyCover(id, file.getBytes());
    }

    @PostMapping("/books/{id}/cover/openlibrary/{coverId}")
    public Book useOpenLibraryCover(@PathVariable String id, @PathVariable long coverId)
            throws IOException, InterruptedException {
        return edits.applyCover(id, coverSearch.downloadOpenLibraryCover(coverId));
    }

    // --- Metadatos --------------------------------------------------------------

    @PatchMapping("/books/{id}")
    public Book updateMetadata(@PathVariable String id, @RequestBody MetadataRequest body) throws IOException {
        requireNotBlankIfPresent(body.title(), "El titulo no puede quedar vacio");
        requireNotBlankIfPresent(body.author(), "El autor no puede quedar vacio");
        String index = body.seriesIndex();
        if (index != null && !index.isBlank()) {
            try {
                if (new java.math.BigDecimal(index.trim().replace(',', '.')).signum() <= 0) {
                    throw new NumberFormatException();
                }
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("El numero en la saga debe ser un numero positivo (1, 2, 2.5...)");
            }
            index = index.trim().replace(',', '.');
        }
        return edits.updateMetadata(id, new EpubMetadataWriter.Changes(
                body.title(), body.author(), body.series(), index));
    }

    @PostMapping("/authors/rename")
    public BookEditService.BulkResult renameAuthor(@RequestBody RenameRequest body) {
        if (body.from() == null || body.to() == null || body.to().isBlank()) {
            throw new IllegalArgumentException("Indica el autor actual y el nuevo nombre");
        }
        return edits.renameAuthor(body.from(), body.to());
    }

    @PostMapping("/series/rename")
    public BookEditService.BulkResult renameSeries(@RequestBody RenameRequest body) {
        if (body.from() == null || body.from().isBlank() || body.to() == null) {
            throw new IllegalArgumentException("Indica la saga actual y el nuevo nombre");
        }
        return edits.renameSeries(body.from(), body.to(), body.author());
    }

    private static void requireNotBlankIfPresent(String value, String message) {
        if (value != null && value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
    }

    // --- Errores ----------------------------------------------------------------

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, String>> notFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "Libro no encontrado"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler({IOException.class, InterruptedException.class})
    public ResponseEntity<Map<String, String>> ioError(Exception e) {
        log.error("Error editando un libro", e);
        if (e instanceof InterruptedException) {
            Thread.currentThread().interrupt();
        }
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(Map.of("error", "No se pudo completar: " + e.getMessage()));
    }
}
