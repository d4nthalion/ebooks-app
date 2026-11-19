package com.bibliodrive.ebooks.edit;

import com.bibliodrive.ebooks.catalog.Book;
import com.bibliodrive.ebooks.catalog.CatalogIndexService;
import com.bibliodrive.ebooks.catalog.EpubCoverWriter;
import com.bibliodrive.ebooks.catalog.EpubMetadataWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * Modifica los epub de la biblioteca: portada y metadatos (titulo, autor, saga).
 *
 * <p>La primera vez que se toca un libro se guarda una copia del fichero original
 * en {@code app.edit-backup-dir}; las ediciones siguientes no la pisan, asi que
 * siempre se puede volver a como estaba antes de usar la app.
 *
 * <p>Las escrituras van de una en una (lock del servicio) y cada libro se
 * reindexa al terminar, sin esperar al reescaneo programado.
 */
@Service
public class BookEditService {

    private static final Logger log = LoggerFactory.getLogger(BookEditService.class);

    /**
     * Resultado de un cambio en bloque. {@code unchanged}: ya tenian ese dato;
     * {@code skipped}: no son epub; {@code failed}: "ruta: motivo".
     */
    public record BulkResult(int updated, int unchanged, List<String> skipped, List<String> failed) {
    }

    private final CatalogIndexService catalog;
    private final EpubCoverWriter coverWriter = new EpubCoverWriter();
    private final EpubMetadataWriter metadataWriter = new EpubMetadataWriter();
    private final com.bibliodrive.ebooks.catalog.EpubMetadataReader metadataReader =
            new com.bibliodrive.ebooks.catalog.EpubMetadataReader();
    private final Path backupDir;

    public BookEditService(CatalogIndexService catalog,
            @Value("${app.edit-backup-dir:edit-backups}") String backupDir) {
        this.catalog = catalog;
        this.backupDir = Path.of(backupDir);
    }

    /**
     * @throws NoSuchElementException   si el libro no existe
     * @throws IllegalArgumentException si no es un epub o la imagen no vale (mensaje para el usuario)
     */
    public Book applyCover(String bookId, byte[] imageBytes) throws IOException {
        Book book = editableEpub(bookId);
        BufferedImage image = EpubCoverWriter.decode(imageBytes);
        synchronized (this) {
            Path file = fileOf(book);
            backupOnce(book, file);
            coverWriter.replaceCover(file, image);
        }
        log.info("Portada cambiada en {} ({}x{})", book.relativePath(), image.getWidth(), image.getHeight());
        return catalog.reindex(bookId).orElse(book);
    }

    public Book updateMetadata(String bookId, EpubMetadataWriter.Changes changes) throws IOException {
        Book book = editableEpub(bookId);
        if (!writeMetadata(book, changes)) {
            return book;
        }
        return catalog.reindex(bookId).orElse(book);
    }

    /** @return true si el epub ha cambiado */
    private boolean writeMetadata(Book book, EpubMetadataWriter.Changes changes) throws IOException {
        if (changes.isEmpty()) {
            return false;
        }
        synchronized (this) {
            Path file = fileOf(book);
            boolean declaresSeries = metadataReader.read(file).series() != null;

            if (changes.series() == null && changes.seriesIndex() != null && !declaresSeries) {
                if (book.series() == null) {
                    throw new IllegalArgumentException("El libro no tiene saga: indica tambien el nombre de la saga");
                }
                // La saga sale de la carpeta: se escribe en el epub junto al numero
                changes = new EpubMetadataWriter.Changes(changes.title(), changes.author(), book.series(),
                        changes.seriesIndex());
            }
            if ("".equals(changes.series()) && !declaresSeries && book.series() != null) {
                throw new IllegalArgumentException("La saga «" + book.series() + "» sale del nombre de la carpeta, "
                        + "no del epub, asi que no se puede quitar desde aqui: mueve el libro fuera de esa carpeta.");
            }

            backupOnce(book, file);
            boolean changed = metadataWriter.update(file, changes);
            if (changed) {
                log.info("Metadatos cambiados en {}: {}", book.relativePath(), changes);
            }
            return changed;
        }
    }

    /** Pone el autor {@code to} en todos los libros cuyo autor es {@code from} (sin distinguir mayusculas). */
    public BulkResult renameAuthor(String from, String to) {
        List<Book> books = catalog.findAll(null, null, null).stream()
                .filter(b -> b.author() != null && b.author().equalsIgnoreCase(from.trim()))
                .toList();
        return bulk(books, new EpubMetadataWriter.Changes(null, to, null, null));
    }

    /**
     * Renombra la saga {@code from} a {@code to} (vacio = quitar la saga). Si se
     * indica autor, solo en sus libros.
     */
    public BulkResult renameSeries(String from, String to, String author) {
        List<Book> books = catalog.findAll(null, author, null).stream()
                .filter(b -> b.series() != null && b.series().equalsIgnoreCase(from.trim()))
                .toList();
        return bulk(books, new EpubMetadataWriter.Changes(null, null, to, null));
    }

    private BulkResult bulk(List<Book> books, EpubMetadataWriter.Changes changes) {
        int updated = 0;
        int unchanged = 0;
        List<String> skipped = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        for (Book book : books) {
            if (!"epub".equals(book.extension())) {
                skipped.add(book.relativePath());
                continue;
            }
            try {
                if (writeMetadata(book, changes)) {
                    catalog.reindex(book.id());
                    updated++;
                } else {
                    unchanged++;
                }
            } catch (IllegalArgumentException e) {
                failed.add(book.relativePath() + ": " + e.getMessage());
            } catch (Exception e) {
                log.error("No se pudo editar {}", book.relativePath(), e);
                failed.add(book.relativePath() + ": " + e.getMessage());
            }
        }
        return new BulkResult(updated, unchanged, skipped, failed);
    }

    private Book editableEpub(String bookId) {
        Book book = catalog.findById(bookId).orElseThrow(NoSuchElementException::new);
        if (!"epub".equals(book.extension())) {
            throw new IllegalArgumentException("Solo se pueden editar los epub");
        }
        return book;
    }

    private Path fileOf(Book book) {
        Path file = catalog.resolveFile(book);
        if (!Files.isRegularFile(file)) {
            throw new NoSuchElementException();
        }
        return file;
    }

    private void backupOnce(Book book, Path file) throws IOException {
        Path backup = backupDir.resolve(book.id().substring(0, 12) + "-" + book.fileName());
        if (Files.exists(backup)) {
            return;
        }
        Files.createDirectories(backupDir);
        // Copia a .part y renombrado atomico: si se corta a medias no queda una copia
        // truncada que luego se daria por buena
        Path partial = backup.resolveSibling(backup.getFileName() + ".part");
        Files.copy(file, partial, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Files.move(partial, backup, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        log.info("Copia del original de {} en {}", book.relativePath(), backup.toAbsolutePath());
    }
}
