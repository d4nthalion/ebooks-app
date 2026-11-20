package com.bibliodrive.ebooks.amule;

import com.bibliodrive.ebooks.catalog.Book;
import com.bibliodrive.ebooks.catalog.EpubMetadataReader;
import com.bibliodrive.ebooks.catalog.EpubMetadataWriter;
import com.bibliodrive.ebooks.catalog.Names;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Decide donde va un libro descargado y lo mueve alli, con el nombre de la
 * biblioteca: {@code Autor/[Saga/]Autor - Titulo.epub}.
 *
 * <ul>
 *   <li>Autor: si ya hay libros suyos, su carpeta de siempre (aunque el epub diga
 *       «Tolkien, J. R. R.» y la carpeta sea «J.R.R. Tolkien»).</li>
 *   <li>Saga: la carpeta en la que ya estan sus otros libros. Si es nueva, una
 *       subcarpeta con su nombre solo si ese autor ya ordena sus sagas en
 *       subcarpetas; si no, junto a sus otros libros.</li>
 * </ul>
 */
public class LibraryPlacer {

    /**
     * Nombre de epublibre: «Sanderson, Brandon - [Nacidos de la bruma 04] Aleacion de ley [75479] (r1.2)»
     * o el de la biblioteca, «Brandon Sanderson - Aleacion de ley».
     */
    private static final Pattern RELEASE = Pattern.compile(
            "^(?<author>[^\\[\\]]+?)\\s+-\\s+(?:\\[(?<series>[^\\]]+?)\\s+(?<index>\\d{1,3}(?:[.,]\\d{1,2})?)\\]\\s*)?"
                    + "(?<title>.+?)\\s*(?:\\[\\d+\\])?\\s*(?:\\(r[\\d.]+\\))?$");

    /** Lo que se sabe del libro: de la peticion, del epub o del nombre del archivo. */
    public record Info(String title, String author, String series, String seriesIndex) {
        boolean complete() {
            return title != null && !title.isBlank() && author != null && !author.isBlank();
        }
    }

    public record Placed(Path target, String relative) {
    }

    private final Path root;
    private final EpubMetadataReader reader = new EpubMetadataReader();
    private final EpubMetadataWriter writer = new EpubMetadataWriter();

    public LibraryPlacer(Path root) {
        this.root = root;
    }

    /**
     * Lo que se sabe del archivo: primero lo que diga el plan (la peticion), luego
     * el epub y, por ultimo, el nombre del archivo.
     */
    public Info identify(Path file, Info plan) {
        Info fromEpub = new Info(null, null, null, null);
        if (file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".epub")) {
            EpubMetadataReader.EpubMetadata m = reader.read(file);
            fromEpub = new Info(m.title(), m.author(), m.series(), m.seriesIndex());
        }
        Info fromName = parseName(file.getFileName().toString());
        Info p = plan == null ? new Info(null, null, null, null) : plan;
        String author = first(p.author(), fromEpub.author(), fromName.author());
        return new Info(
                first(p.title(), fromEpub.title(), fromName.title()),
                author == null ? null : unflip(author),
                first(p.series(), fromEpub.series(), fromName.series()),
                p.series() != null ? p.seriesIndex()
                        : fromEpub.series() != null ? fromEpub.seriesIndex() : fromName.seriesIndex());
    }

    static Info parseName(String fileName) {
        String base = fileName.replaceFirst("\\.[A-Za-z0-9]{2,5}$", "").replace('_', ' ').trim();
        Matcher m = RELEASE.matcher(base);
        if (!m.matches()) return new Info(null, null, null, null);
        String index = m.group("index");
        return new Info(m.group("title").trim(), m.group("author").trim(),
                m.group("series") == null ? null : m.group("series").trim(),
                index == null ? null : new java.math.BigDecimal(index.replace(',', '.')).stripTrailingZeros().toPlainString());
    }

    /** «Sanderson, Brandon» → «Brandon Sanderson» */
    static String unflip(String author) {
        int comma = author.indexOf(',');
        if (comma < 0 || author.indexOf(',', comma + 1) >= 0) return author.trim();
        return author.substring(comma + 1).trim() + " " + author.substring(0, comma).trim();
    }

    /** Ruta relativa a la biblioteca en la que deberia ir. */
    public String targetFor(Info info, String extension, List<Book> library) {
        String authorFolder = authorFolder(info.author(), library);
        String sagaFolder = sagaFolder(authorFolder, info.series(), library);
        String name = Names.safeFileName(authorFolder) + " - " + Names.safeFileName(info.title()) + "." + extension;
        return (sagaFolder == null ? authorFolder : authorFolder + "/" + sagaFolder) + "/" + name;
    }

    private String authorFolder(String author, List<Book> library) {
        for (Book b : library) {
            Path rel = Path.of(b.relativePath());
            if (rel.getNameCount() < 2) continue;
            String folder = rel.getName(0).toString();
            if (Names.sameAuthor(b.author(), author) || Names.sameAuthor(folder, author)) return folder;
        }
        return Names.safeFileName(author);
    }

    private String sagaFolder(String authorFolder, String series, List<Book> library) {
        List<Path> authorBooks = library.stream()
                .map(b -> Path.of(b.relativePath()))
                .filter(p -> p.getNameCount() >= 2 && p.getName(0).toString().equals(authorFolder))
                .toList();
        if (series != null) {
            Optional<Path> sameSaga = library.stream()
                    .filter(b -> b.series() != null && Names.key(b.series()).equals(Names.key(series)))
                    .map(b -> Path.of(b.relativePath()))
                    .filter(p -> p.getNameCount() >= 2 && p.getName(0).toString().equals(authorFolder))
                    .findFirst();
            if (sameSaga.isPresent()) {
                Path parent = sameSaga.get().getParent();
                return parent.getNameCount() > 1 ? parent.subpath(1, parent.getNameCount()).toString().replace('\\', '/') : null;
            }
            boolean usesSubfolders = authorBooks.stream().anyMatch(p -> p.getNameCount() > 2);
            return usesSubfolders ? Names.safeFileName(series) : null;
        }
        return null;
    }

    /**
     * Mueve el archivo a su sitio (copia a .part y renombra: puede ser otro volumen
     * del NAS) y, si es un epub sin saga y se sabe cual es, se la escribe.
     */
    public Placed place(Path file, Info info, String relative) throws IOException {
        Path target = root.resolve(relative).normalize();
        if (!target.startsWith(root.normalize())) throw new IOException("Destino fuera de la biblioteca");
        Files.createDirectories(target.getParent());
        target = free(target);
        Path partial = target.resolveSibling(target.getFileName() + ".part");
        Files.copy(file, partial, StandardCopyOption.REPLACE_EXISTING);
        Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE);
        Files.delete(file);

        if (target.toString().toLowerCase(Locale.ROOT).endsWith(".epub") && info.series() != null) {
            try {
                if (reader.read(target).series() == null) {
                    writer.update(target, new EpubMetadataWriter.Changes(null, null, info.series(), info.seriesIndex()));
                }
            } catch (IOException | RuntimeException ignored) {
                // la saga es un extra: si el epub no se deja, queda bien colocado igualmente
            }
        }
        return new Placed(target, root.relativize(target).toString().replace('\\', '/'));
    }

    /** «X.epub» ya existe: «X (2).epub» */
    private static Path free(Path target) {
        if (!Files.exists(target)) return target;
        String name = target.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String stem = dot < 0 ? name : name.substring(0, dot);
        String ext = dot < 0 ? "" : name.substring(dot);
        for (int i = 2; ; i++) {
            Path candidate = target.resolveSibling(stem + " (" + i + ")" + ext);
            if (!Files.exists(candidate)) return candidate;
        }
    }

    private static String first(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v.trim();
        }
        return null;
    }
}
