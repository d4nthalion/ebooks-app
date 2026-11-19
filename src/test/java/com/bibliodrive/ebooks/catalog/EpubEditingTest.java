package com.bibliodrive.ebooks.catalog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EpubEditingTest {

    private static final String OPF_WITH_COVER = """
            <?xml version="1.0" encoding="utf-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" unique-identifier="BookID" version="2.0">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:opf="http://www.idpf.org/2007/opf">
                <dc:title>Titulo viejo</dc:title>
                <dc:creator opf:role="aut" opf:file-as="Tolkien, J.R.R.">J.R.R. Tolkien</dc:creator>
                <meta name="calibre:series" content="El se&#241;or de los anillos"/>
                <meta name="calibre:series_index" content="2.0"/>
                <meta name="cover" content="cover.jpg"/>
              </metadata>
              <manifest>
                <item href="Images/cover.jpg" id="cover.jpg" media-type="image/jpeg"/>
                <item href="Text/cubierta.xhtml" id="cubierta" media-type="application/xhtml+xml"/>
              </manifest>
              <spine><itemref idref="cubierta"/></spine>
            </package>
            """;

    private static final String OPF_NO_COVER = """
            <?xml version="1.0" encoding="utf-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:title>Sin portada</dc:title>
                <dc:creator id="c1">Di Urarte, Carlos</dc:creator>
                <meta refines="#c1" property="file-as">Di Urarte, Carlos</meta>
                <meta property="belongs-to-collection" id="col">Saga vieja</meta>
                <meta refines="#col" property="group-position">3</meta>
              </metadata>
              <manifest>
                <item href="Text/cap1.xhtml" id="cap1" media-type="application/xhtml+xml"/>
              </manifest>
              <spine><itemref idref="cap1"/></spine>
            </package>
            """;

    private static final String SVG_COVER_PAGE = """
            <html xmlns="http://www.w3.org/1999/xhtml"><body>
            <svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" version="1.1" width="100%" height="100%" viewBox="0 0 518 751" preserveAspectRatio="none">
            <image width="518" height="751" xlink:href="../Images/cover.jpg"/></svg></body></html>
            """;

    private final EpubMetadataReader reader = new EpubMetadataReader();

    @Test
    void cambiaTituloAutorYSagaEnEpub2(@TempDir Path dir) throws IOException {
        Path epub = buildEpub(dir.resolve("libro.epub"), OPF_WITH_COVER, true);

        new EpubMetadataWriter().update(epub,
                new EpubMetadataWriter.Changes("Las dos torres", "J. R. R. Tolkien", "El Señor de los Anillos", "2"));

        EpubMetadataReader.EpubMetadata meta = reader.read(epub);
        assertEquals("Las dos torres", meta.title());
        assertEquals("J. R. R. Tolkien", meta.author());
        assertEquals("El Señor de los Anillos", meta.series());
        assertEquals("2", meta.seriesIndex());
        assertNotNull(meta.cover(), "la portada se conserva");

        String opf = readEntry(epub, "OEBPS/content.opf");
        assertFalse(opf.contains("file-as"), "el file-as viejo se quita");
        assertEquals(1, count(opf, "calibre:series\""), "una sola saga");
        assertMimetypeFirstAndStored(epub);
    }

    @Test
    void soloNumeroConservaLaSaga() {
        String opf = EpubMetadataWriter.apply(OPF_WITH_COVER, new EpubMetadataWriter.Changes(null, null, null, "3"));
        assertTrue(opf.contains("content=\"El se&#241;or de los anillos\""));
        assertTrue(opf.contains("calibre:series_index\" content=\"3\""));
    }

    @Test
    void sagaVaciaLaQuita(@TempDir Path dir) throws IOException {
        Path epub = buildEpub(dir.resolve("libro.epub"), OPF_NO_COVER, false);
        new EpubMetadataWriter().update(epub, new EpubMetadataWriter.Changes(null, null, "", null));

        assertNull(reader.read(epub).series());
        String opf = readEntry(epub, "OEBPS/content.opf");
        assertFalse(opf.contains("belongs-to-collection"));
        assertFalse(opf.contains("group-position"));
    }

    @Test
    void autorConCaracteresEspecialesYRefinesEpub3(@TempDir Path dir) throws IOException {
        Path epub = buildEpub(dir.resolve("libro.epub"), OPF_NO_COVER, false);
        new EpubMetadataWriter().update(epub,
                new EpubMetadataWriter.Changes(null, "Carlos di Urarte & <otro>", "Saga nueva", "1"));

        EpubMetadataReader.EpubMetadata meta = reader.read(epub);
        assertEquals("Carlos di Urarte & <otro>", meta.author());
        assertEquals("Saga nueva", meta.series());
        assertEquals("1", meta.seriesIndex());
        String opf = readEntry(epub, "OEBPS/content.opf");
        assertFalse(opf.contains("file-as"));
        assertTrue(opf.contains("<meta property=\"belongs-to-collection\" id=\"col\">Saga nueva</meta>"));
    }

    @Test
    void reemplazaPortadaExistenteYAjustaSvg(@TempDir Path dir) throws IOException {
        Path epub = buildEpub(dir.resolve("libro.epub"), OPF_WITH_COVER, true);

        new EpubCoverWriter().replaceCover(epub, image(400, 600));

        EpubMetadataReader.EpubMetadata meta = reader.read(epub);
        BufferedImage cover = ImageIO.read(new ByteArrayInputStream(meta.cover().data()));
        assertEquals(400, cover.getWidth());
        assertEquals(600, cover.getHeight());
        assertEquals("Titulo viejo", meta.title(), "no toca otros metadatos");

        String page = readEntry(epub, "OEBPS/Text/cubierta.xhtml");
        assertTrue(page.contains("viewBox=\"0 0 400 600\""));
        assertTrue(page.contains("<image width=\"400\" height=\"600\""));
        assertMimetypeFirstAndStored(epub);
        assertFalse(Files.exists(dir.resolve(".libro.epub.tmp")));
    }

    @Test
    void anadePortadaSiNoTiene(@TempDir Path dir) throws IOException {
        Path epub = buildEpub(dir.resolve("libro.epub"), OPF_NO_COVER, false);
        assertNull(reader.read(epub).cover());

        new EpubCoverWriter().replaceCover(epub, image(300, 450));

        EpubMetadataReader.EpubMetadata meta = reader.read(epub);
        assertNotNull(meta.cover());
        String opf = readEntry(epub, "OEBPS/content.opf");
        assertTrue(opf.contains("properties=\"cover-image\""), "EPUB 3 declara cover-image");
        assertTrue(opf.contains("<meta name=\"cover\" content=\"ebooks-api-cover\"/>"));
    }

    @Test
    void renombrarSagaEpub3SinNumeroConservaElNumero() {
        String opf = EpubMetadataWriter.apply(OPF_NO_COVER, new EpubMetadataWriter.Changes(null, null, "Saga nueva", null));
        assertTrue(opf.contains("<meta refines=\"#col\" property=\"group-position\">3</meta>"), opf);
        assertTrue(opf.contains("calibre:series_index\" content=\"3\""), opf);
        assertTrue(opf.contains(">Saga nueva</meta>"));
    }

    @Test
    void metaNoAutocerradaNoSeDuplica() {
        String opf = OPF_WITH_COVER.replace("<meta name=\"calibre:series\" content=\"El se&#241;or de los anillos\"/>",
                "<meta name=\"calibre:series\" content=\"Vieja\"></meta>");
        String result = EpubMetadataWriter.apply(opf, new EpubMetadataWriter.Changes(null, null, "Nueva", null));
        assertFalse(result.contains("Vieja"), result);
        assertEquals(1, count(result, "calibre:series\""));
    }

    @Test
    void noMachacaLaContraportadaSiNoHayPortadaDeclarada(@TempDir Path dir) throws IOException {
        String opf = OPF_NO_COVER.replace("<item href=\"Text/cap1.xhtml\"",
                "<item href=\"Images/contraportada.jpg\" id=\"contra\" media-type=\"image/jpeg\"/>\n    <item href=\"Text/cap1.xhtml\"");
        Path epub = buildEpub(dir.resolve("libro.epub"), opf, false);
        try (var fs = java.nio.file.FileSystems.newFileSystem(epub)) {
            java.io.ByteArrayOutputStream jpg = new java.io.ByteArrayOutputStream();
            ImageIO.write(new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB), "jpeg", jpg);
            Files.createDirectories(fs.getPath("OEBPS/Images"));
            Files.write(fs.getPath("OEBPS/Images/contraportada.jpg"), jpg.toByteArray());
        }
        byte[] before = readBytes(epub, "OEBPS/Images/contraportada.jpg");

        new EpubCoverWriter().replaceCover(epub, image(300, 450));

        assertTrue(java.util.Arrays.equals(before, readBytes(epub, "OEBPS/Images/contraportada.jpg")),
                "la contraportada no se toca");
        assertEquals(300, ImageIO.read(new ByteArrayInputStream(reader.read(epub).cover().data())).getWidth());
    }

    @Test
    void svgDeOtraImagenConNombreParecidoNoSeToca(@TempDir Path dir) throws IOException {
        Path epub = buildEpub(dir.resolve("libro.epub"), OPF_WITH_COVER, true);
        String other = "<html><body><svg viewBox=\"0 0 10 20\"><image width=\"10\" height=\"20\" xlink:href=\"../Images/backcover.jpg\"/></svg></body></html>";
        try (var fs = java.nio.file.FileSystems.newFileSystem(epub)) {
            Files.writeString(fs.getPath("OEBPS/Text/otra.xhtml"), other);
        }

        new EpubCoverWriter().replaceCover(epub, image(400, 600));

        assertEquals(other, readEntry(epub, "OEBPS/Text/otra.xhtml"));
        assertTrue(readEntry(epub, "OEBPS/Text/cubierta.xhtml").contains("viewBox=\"0 0 400 600\""));
    }

    @Test
    void controlCharsSeQuitan() {
        String opf = EpubMetadataWriter.apply(OPF_WITH_COVER,
                new EpubMetadataWriter.Changes("Hola\u0007 mundo", null, null, null));
        assertTrue(opf.contains("<dc:title>Hola mundo</dc:title>"));
    }

    private static byte[] readBytes(Path epub, String name) throws IOException {
        try (ZipFile zip = new ZipFile(epub.toFile())) {
            return zip.getInputStream(zip.getEntry(name)).readAllBytes();
        }
    }

    @Test
    void imagenNoValidaDaErrorClaro() {
        try {
            EpubCoverWriter.decode("no soy una imagen".getBytes(StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("JPG o PNG"));
            return;
        }
        throw new AssertionError("deberia fallar");
    }

    // --- helpers ----------------------------------------------------------------

    private static BufferedImage image(int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        img.setRGB(0, 0, 0x80FF0000);
        return img;
    }

    private static Path buildEpub(Path target, String opf, boolean withCover) throws IOException {
        try (OutputStream fileOut = Files.newOutputStream(target);
             ZipOutputStream zip = new ZipOutputStream(fileOut, StandardCharsets.UTF_8)) {
            byte[] mimetype = "application/epub+zip".getBytes(StandardCharsets.US_ASCII);
            ZipEntry entry = new ZipEntry("mimetype");
            entry.setMethod(ZipEntry.STORED);
            entry.setSize(mimetype.length);
            CRC32 crc = new CRC32();
            crc.update(mimetype);
            entry.setCrc(crc.getValue());
            zip.putNextEntry(entry);
            zip.write(mimetype);
            zip.closeEntry();

            put(zip, "META-INF/container.xml", """
                    <?xml version="1.0"?>
                    <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                      <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
                    </container>""".getBytes(StandardCharsets.UTF_8));
            put(zip, "OEBPS/content.opf", opf.getBytes(StandardCharsets.UTF_8));
            if (withCover) {
                java.io.ByteArrayOutputStream jpg = new java.io.ByteArrayOutputStream();
                ImageIO.write(new BufferedImage(518, 751, BufferedImage.TYPE_INT_RGB), "jpeg", jpg);
                put(zip, "OEBPS/Images/cover.jpg", jpg.toByteArray());
                put(zip, "OEBPS/Text/cubierta.xhtml", SVG_COVER_PAGE.getBytes(StandardCharsets.UTF_8));
            } else {
                put(zip, "OEBPS/Text/cap1.xhtml", "<html/>".getBytes(StandardCharsets.UTF_8));
            }
        }
        return target;
    }

    private static void put(ZipOutputStream zip, String name, byte[] data) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(data);
        zip.closeEntry();
    }

    private static String readEntry(Path epub, String name) throws IOException {
        try (ZipFile zip = new ZipFile(epub.toFile())) {
            return new String(zip.getInputStream(zip.getEntry(name)).readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void assertMimetypeFirstAndStored(Path epub) throws IOException {
        try (ZipFile zip = new ZipFile(epub.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            ZipEntry first = entries.nextElement();
            assertEquals("mimetype", first.getName());
            assertEquals(ZipEntry.STORED, first.getMethod());
        }
    }

    private static int count(String text, String needle) {
        int n = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + 1)) {
            n++;
        }
        return n;
    }
}
