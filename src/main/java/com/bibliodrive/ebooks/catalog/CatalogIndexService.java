package com.bibliodrive.ebooks.catalog;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Indice en memoria de la biblioteca. No hay base de datos: la carpeta del NAS
 * (o local mientras tanto) es la unica fuente de verdad. El indice se reconstruye
 * al arrancar y bajo demanda/programado, escaneando el arbol de carpetas.
 */
@Service
public class CatalogIndexService {

    private static final Logger log = LoggerFactory.getLogger(CatalogIndexService.class);

    private final Path rootPath;
    private final Set<String> allowedExtensions;
    private final EpubMetadataReader epubReader = new EpubMetadataReader();

    private volatile Map<String, Book> booksById = Map.of();
    private volatile Map<String, EpubMetadataReader.CoverImage> coversById = Map.of();
    private volatile long lastIndexedAt = 0L;
    private org.springframework.context.ApplicationEventPublisher events;

    /** Se publica tras cada escaneo completo (las peticiones miran si ya ha llegado lo pedido). */
    public record RefreshedEvent(int books) {
    }


    public CatalogIndexService(
            @Value("${ebooks.root-path}") String rootPath,
            @Value("${ebooks.allowed-extensions}") String allowedExtensions) {
        this.rootPath = Path.of(rootPath);
        Set<String> extensions = new HashSet<>();
        for (String ext : allowedExtensions.split(",")) {
            if (!ext.isBlank()) {
                extensions.add(ext.trim().toLowerCase(java.util.Locale.ROOT));
            }
        }
        this.allowedExtensions = extensions;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setEvents(org.springframework.context.ApplicationEventPublisher events) {
        this.events = events;
    }

    @PostConstruct
    public void init() {
        refresh();
    }

    public Path rootPath() {
        return rootPath;
    }

    @Scheduled(fixedDelayString = "${ebooks.auto-refresh-minutes}", timeUnit = TimeUnit.MINUTES)
    public void scheduledRefresh() {
        refresh();
    }

    public synchronized void refresh() {
        if (!Files.isDirectory(rootPath)) {
            log.warn("La carpeta raiz de ebooks no existe todavia: {}", rootPath);
            return;
        }

        Map<String, Book> newBooks = new LinkedHashMap<>();
        Map<String, EpubMetadataReader.CoverImage> newCovers = new HashMap<>();

        try (Stream<Path> paths = Files.walk(rootPath)) {
            List<Path> files = paths.filter(Files::isRegularFile).sorted().toList();
            for (Path file : files) {
                String extension = extensionOf(file);
                if (!allowedExtensions.contains(extension)) {
                    continue;
                }

                Indexed indexed = index(file, extension);
                newBooks.put(indexed.book().id(), indexed.book());
                if (indexed.cover() != null) {
                    newCovers.put(indexed.book().id(), indexed.cover());
                }
            }
        } catch (IOException e) {
            log.error("Error escaneando {}", rootPath, e);
            return;
        }

        this.booksById = newBooks;
        this.coversById = newCovers;
        this.lastIndexedAt = System.currentTimeMillis();
        log.info("Indexados {} libros desde {}", newBooks.size(), rootPath);
        if (events != null) {
            events.publishEvent(new RefreshedEvent(newBooks.size()));
        }
    }

    private record Indexed(Book book, EpubMetadataReader.CoverImage cover) {
    }

    private Indexed index(Path file, String extension) throws IOException {
        Path relative = rootPath.relativize(file);
        String id = hashOf(relative.toString());

        String title = fileNameWithoutExtension(file);
        String author = inferAuthor(relative);
        // La saga solo sale del epub (Calibre o esta app), nunca del nombre de la
        // carpeta: asi se edita a mano y una carpeta «Los Diablos» no inventa sagas
        String series = null;
        String seriesIndex = null;
        EpubMetadataReader.CoverImage cover = null;

        if ("epub".equals(extension)) {
            EpubMetadataReader.EpubMetadata meta = epubReader.read(file);
            if (meta.title() != null && !meta.title().isBlank()) {
                title = meta.title();
            }
            if (meta.author() != null && !meta.author().isBlank()) {
                author = meta.author();
            }
            if (meta.series() != null) {
                series = meta.series();
                seriesIndex = meta.seriesIndex();
            }
            cover = meta.cover();
        }

        long size = Files.size(file);
        long modified = Files.getLastModifiedTime(file).toMillis();

        Book book = new Book(id, title, author, series, seriesIndex, file.getFileName().toString(),
                relative.toString(), extension, size, modified, cover != null);
        return new Indexed(book, cover);
    }

    /**
     * Vuelve a leer un solo libro (tras modificar su fichero) sin reescanear toda
     * la carpeta. Devuelve el libro actualizado.
     */
    public synchronized Optional<Book> reindex(String id) {
        Book current = booksById.get(id);
        if (current == null) {
            return Optional.empty();
        }
        Path file = resolveFile(current);
        try {
            Indexed indexed = index(file, current.extension());
            Map<String, Book> books = new LinkedHashMap<>(booksById);
            Map<String, EpubMetadataReader.CoverImage> covers = new HashMap<>(coversById);
            books.put(id, indexed.book());
            if (indexed.cover() != null) {
                covers.put(id, indexed.cover());
            } else {
                covers.remove(id);
            }
            this.booksById = books;
            this.coversById = covers;
            return Optional.of(indexed.book());
        } catch (IOException e) {
            log.error("Error reindexando {}", file, e);
            return Optional.of(current);
        }
    }

    public List<Book> findAll(String search, String author, String series) {
        String searchLower = search == null ? null : search.toLowerCase(java.util.Locale.ROOT);
        return booksById.values().stream()
                .filter(b -> searchLower == null || searchLower.isBlank()
                        || b.title().toLowerCase(java.util.Locale.ROOT).contains(searchLower)
                        || (b.author() != null && b.author().toLowerCase(java.util.Locale.ROOT).contains(searchLower)))
                .filter(b -> author == null || author.isBlank() || author.equalsIgnoreCase(b.author()))
                .filter(b -> series == null || series.isBlank() || series.equalsIgnoreCase(b.series()))
                .sorted(Comparator.comparing(Book::author, Comparator.nullsLast(String::compareToIgnoreCase))
                        .thenComparing(Book::series, Comparator.nullsLast(String::compareToIgnoreCase))
                        .thenComparing(CatalogIndexService::seriesPosition, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(Book::title, Comparator.nullsLast(String::compareToIgnoreCase)))
                .toList();
    }

    private static java.math.BigDecimal seriesPosition(Book book) {
        if (book.seriesIndex() == null) {
            return null;
        }
        try {
            return new java.math.BigDecimal(book.seriesIndex());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public Optional<Book> findById(String id) {
        return Optional.ofNullable(booksById.get(id));
    }

    public Optional<EpubMetadataReader.CoverImage> coverOf(String id) {
        return Optional.ofNullable(coversById.get(id));
    }

    public Path resolveFile(Book book) {
        return rootPath.resolve(book.relativePath());
    }

    public List<String> authors() {
        return booksById.values().stream()
                .map(Book::author)
                .filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.toCollection(() -> new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER)))
                .stream()
                .toList();
    }

    public List<String> seriesList(String author) {
        return booksById.values().stream()
                .filter(b -> author == null || author.isBlank() || author.equalsIgnoreCase(b.author()))
                .map(Book::series)
                .filter(Objects::nonNull)
                .collect(java.util.stream.Collectors.toCollection(() -> new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER)))
                .stream()
                .toList();
    }

    public long lastIndexedAt() {
        return lastIndexedAt;
    }

    public int size() {
        return booksById.size();
    }

    private String extensionOf(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
    }

    private String fileNameWithoutExtension(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }

    private String inferAuthor(Path relative) {
        if (relative.getNameCount() >= 2) {
            return relative.getName(0).toString();
        }
        return "Desconocido";
    }

    private String hashOf(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
