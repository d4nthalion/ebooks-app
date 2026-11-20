package com.bibliodrive.ebooks.amule;

import com.bibliodrive.ebooks.catalog.Book;
import com.bibliodrive.ebooks.catalog.Names;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LibraryPlacerTest {

    private static Book book(String path, String author, String series) {
        return new Book(path, "x", author, series, null, Path.of(path).getFileName().toString(), path, "epub", 1, 1, false);
    }

    private final List<Book> library = List.of(
            book("Brandon Sanderson/Nacidos de la bruma (Cosmere)/Brandon Sanderson - El Imperio Final.epub",
                    "Brandon Sanderson", "Nacidos de la Bruma"),
            book("Brandon Sanderson/Standalone (Cosmere)/Brandon Sanderson - Elantris.epub", "Brandon Sanderson", null),
            book("Dan Simmons/Dan Simmons - Hyperion.epub", "Dan Simmons", "Cantos de Hyperion"),
            book("J.R.R. Tolkien/El Hobbit/J.R.R. Tolkien - El Hobbit.epub", "J.R.R. Tolkien", "El Hobbit"));

    @Test
    void readsEpublibreNames() {
        var info = LibraryPlacer.parseName("Sanderson, Brandon - [Nacidos de la bruma 04] Aleacion de ley (Ed. ilustrada) [75479] (r1.2).epub");
        assertEquals("Sanderson, Brandon", info.author());
        assertEquals("Nacidos de la bruma", info.series());
        assertEquals("4", info.seriesIndex());
        assertEquals("Aleacion de ley (Ed. ilustrada)", info.title());

        var half = LibraryPlacer.parseName("Sanderson, Brandon - [El archivo de las tormentas 2.5] Danzante del Filo [43455] (r1.0).epub");
        assertEquals("2.5", half.seriesIndex());
        assertEquals("Danzante del Filo", half.title());

        var plain = LibraryPlacer.parseName("Brandon Sanderson - Juramentada.epub");
        assertEquals("Brandon Sanderson", plain.author());
        assertEquals("Juramentada", plain.title());
        assertNull(plain.series());

        assertNull(LibraryPlacer.parseName("libro_raro_v2.epub").title());
        assertEquals("Brandon Sanderson", LibraryPlacer.unflip("Sanderson, Brandon"));
    }

    @Test
    void choosesAuthorAndSagaFolders(@TempDir Path root) {
        LibraryPlacer placer = new LibraryPlacer(root);
        // saga que ya existe: a su carpeta, aunque el nombre cambie de mayusculas
        assertEquals("Brandon Sanderson/Nacidos de la bruma (Cosmere)/Brandon Sanderson - El metal perdido.epub",
                placer.targetFor(new LibraryPlacer.Info("El metal perdido", "Brandon Sanderson", "Nacidos de la bruma", "7"),
                        "epub", library));
        // saga nueva de un autor que usa subcarpetas: subcarpeta nueva
        assertEquals("Brandon Sanderson/El archivo de las tormentas/Brandon Sanderson - Juramentada.epub",
                placer.targetFor(new LibraryPlacer.Info("Juramentada", "Sanderson, Brandon", "El archivo de las tormentas", "3"),
                        "epub", library));
        // autor con sus libros sueltos en la raiz: junto a ellos
        assertEquals("Dan Simmons/Dan Simmons - Endymion.epub",
                placer.targetFor(new LibraryPlacer.Info("Endymion", "Dan Simmons", "Cantos de Hyperion", "3"), "epub", library));
        // el autor escrito de otra forma va a su carpeta de siempre
        assertEquals("J.R.R. Tolkien/J.R.R. Tolkien - El Silmarillion.epub",
                placer.targetFor(new LibraryPlacer.Info("El Silmarillion", "J. R. R. Tolkien", null, null), "epub", library));
        // autor nuevo
        assertEquals("Ursula K. Le Guin/Ursula K. Le Guin - Un mago de Terramar - edición ilustrada.epub",
                placer.targetFor(new LibraryPlacer.Info("Un mago de Terramar: edición ilustrada", "Ursula K. Le Guin", null, null),
                        "epub", library));
    }

    @Test
    void movesWithoutOverwriting(@TempDir Path root, @TempDir Path incoming) throws Exception {
        LibraryPlacer placer = new LibraryPlacer(root);
        Path a = Files.writeString(incoming.resolve("a.pdf"), "uno");
        Path b = Files.writeString(incoming.resolve("b.pdf"), "dos");
        var info = new LibraryPlacer.Info("Libro", "Autora", null, null);
        var first = placer.place(a, info, "Autora/Autora - Libro.pdf");
        var second = placer.place(b, info, "Autora/Autora - Libro.pdf");
        assertEquals("Autora/Autora - Libro.pdf", first.relative());
        assertEquals("Autora/Autora - Libro (2).pdf", second.relative());
        assertFalse(Files.exists(a));
        assertEquals("dos", Files.readString(second.target()));
    }

    @Test
    void comparesTitlesAndAuthors() {
        assertEquals(Names.titleKey("El camino de los reyes"), Names.titleKey("Camino de los reyes"));
        assertEquals(Names.titleKey("Dune (Edición ilustrada)"), Names.titleKey("Dune"));
        assertTrue(Names.sameAuthor("Di Urarte, Carlos", "Carlos di Urarte"));
        assertTrue(Names.sameAuthor("J. R. R. Tolkien", "J.R.R. Tolkien"));
        assertFalse(Names.sameAuthor("Brandon Sanderson", "Dan Simmons"));
        assertEquals("Camino de los reyes (el archivo de las tormentas 1)",
                Names.tidyCase("CAMINO DE LOS REYES (EL ARCHIVO DE LAS TORMENTAS 1)"));
    }
}
