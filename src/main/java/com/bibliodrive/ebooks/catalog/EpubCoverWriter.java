package com.bibliodrive.ebooks.catalog;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Cambia la portada de un epub sin herramientas externas.
 *
 * <ul>
 *   <li>Si el epub ya tiene portada (jpg/png), se sobreescribe esa misma imagen,
 *       asi las paginas de cubierta que la referencian siguen funcionando.</li>
 *   <li>Si no tiene, se anade una imagen nueva y se declara en el OPF
 *       (meta name="cover" y, en EPUB 3, properties="cover-image").</li>
 *   <li>Las cubiertas en SVG (viewBox fijo) se ajustan a las nuevas medidas para
 *       que la imagen no salga deformada.</li>
 * </ul>
 *
 * El fichero se escribe primero a un temporal en la misma carpeta y luego se
 * mueve encima del original, para no dejar nunca un epub a medio escribir.
 */
public class EpubCoverWriter {

    /** Altura maxima de la portada guardada (lo que recomienda Amazon para Kindle). */
    private static final int MAX_HEIGHT = 2560;
    private static final String NEW_COVER_ID = "ebooks-api-cover";

    private static final Pattern SVG_VIEWBOX = Pattern.compile("(<svg\\b[^>]*?\\bviewBox=\")0 0 [\\d.]+ [\\d.]+(\")");
    private static final Pattern SVG_BLOCK = Pattern.compile("<svg\\b.*?</svg\\s*>", Pattern.DOTALL);
    private static final Pattern IMAGE_TAG = Pattern.compile("<image\\b[^>]*>");
    private static final Pattern WIDTH_ATTR = Pattern.compile("(\\swidth=\")[\\d.]+(\")");
    private static final Pattern HEIGHT_ATTR = Pattern.compile("(\\sheight=\")[\\d.]+(\")");

    private final EpubMetadataReader reader = new EpubMetadataReader();

    /**
     * Convierte los bytes recibidos (jpg/png/gif/bmp) a imagen. Lanza
     * IllegalArgumentException con un mensaje para el usuario si no se puede.
     */
    public static BufferedImage decode(byte[] imageBytes) {
        BufferedImage image;
        try {
            image = ImageIO.read(new ByteArrayInputStream(imageBytes));
        } catch (IOException | RuntimeException e) {
            image = null;
        }
        if (image == null) {
            throw new IllegalArgumentException(
                    "No se pudo leer la imagen. Usa un JPG o PNG (los JPG en CMYK y los WEBP no se admiten).");
        }
        return image;
    }

    public void replaceCover(Path epub, BufferedImage newCover) throws IOException {
        BufferedImage image = scaleDown(newCover);
        ZipFile zip = new ZipFile(epub.toFile(), StandardCharsets.UTF_8);
        // Ruta del zip -> contenido nuevo
        Map<String, byte[]> replacements = new HashMap<>();
        try {
            String opfPath;
            Document opfDoc;
            try {
                opfPath = reader.findOpfPath(zip);
                if (opfPath == null) {
                    throw new IllegalArgumentException("El epub no tiene META-INF/container.xml valido");
                }
                opfDoc = reader.parseXml(zip, opfPath);
            } catch (IllegalArgumentException | IOException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalArgumentException("No se pudo leer el OPF del epub: " + e.getMessage(), e);
            }

            String opfText = EpubZip.readText(zip, opfPath);
            String newOpfText = opfText;
            String coverPath;

            EpubMetadataReader.CoverLocation existing = reader.locateCover(opfDoc, opfPath);
            // Solo se sobreescribe en su sitio si es seguro que es la portada: declarada en
            // el OPF, o llamada exactamente cover/portada (no "contraportada.jpg" ni "backcover.jpg")
            boolean safeInPlace = existing != null
                    && (existing.declared() || isExactCoverName(existing.zipPath()))
                    && zip.getEntry(existing.zipPath()) != null
                    && formatOf(existing.zipPath()) != null;
            if (safeInPlace) {
                coverPath = existing.zipPath();
                replacements.put(coverPath, encode(image, formatOf(coverPath)));
                if (!existing.declared()) {
                    // Encontrada solo por el nombre: la declaramos para que Kindle/Calibre la vean
                    newOpfText = EpubZip.insertBefore(newOpfText, "metadata",
                            "<meta name=\"cover\" content=\"" + existing.itemId() + "\"/>");
                }
            } else {
                // Sin portada (o en un formato que no reescribimos): imagen nueva declarada en el OPF
                String opfDir = EpubMetadataReader.opfDirOf(opfPath);
                String href = uniqueHref(zip, opfDir, "cover-ebooks-api.jpg");
                coverPath = EpubMetadataReader.resolveRelative(opfDir, href);
                replacements.put(coverPath, encode(image, "jpeg"));

                String itemId = uniqueId(opfDoc);
                boolean epub3 = opfDoc.getDocumentElement().getAttribute("version").startsWith("3");
                newOpfText = removeCoverDeclarations(newOpfText);
                newOpfText = EpubZip.insertBefore(newOpfText, "manifest",
                        "<item id=\"" + itemId + "\" href=\"" + href + "\" media-type=\"image/jpeg\""
                                + (epub3 ? " properties=\"cover-image\"" : "") + "/>");
                newOpfText = EpubZip.insertBefore(newOpfText, "metadata",
                        "<meta name=\"cover\" content=\"" + itemId + "\"/>");
            }
            if (!newOpfText.equals(opfText)) {
                replacements.put(opfPath, newOpfText.getBytes(StandardCharsets.UTF_8));
            }

            fixSvgCoverPages(zip, coverPath, image, replacements);
        } catch (RuntimeException | IOException e) {
            zip.close();
            throw e;
        }
        EpubZip.rewrite(zip, epub, replacements);
    }

    // --- Imagen -----------------------------------------------------------------

    private static BufferedImage scaleDown(BufferedImage src) {
        if (src.getHeight() <= MAX_HEIGHT) {
            return src;
        }
        int height = MAX_HEIGHT;
        int width = Math.max(1, Math.round(src.getWidth() * (height / (float) src.getHeight())));
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(src, 0, 0, width, height, null);
        g.dispose();
        return out;
    }

    private static boolean isExactCoverName(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        return name.matches("(cover|portada|cubierta)\\.(jpe?g|png)");
    }

    /** "jpeg", "png" o null si la extension no es una que sepamos reescribir. */
    private static String formatOf(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "jpeg";
        if (lower.endsWith(".png")) return "png";
        return null;
    }

    private static byte[] encode(BufferedImage image, String format) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if ("png".equals(format)) {
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        }

        // JPEG no admite transparencia: se aplana sobre blanco
        BufferedImage rgb = new BufferedImage(image.getWidth(), image.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, rgb.getWidth(), rgb.getHeight());
        g.drawImage(image, 0, 0, null);
        g.dispose();

        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(ios);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(0.9f);
            writer.write(null, new IIOImage(rgb, null, null), param);
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    // --- OPF --------------------------------------------------------------------

    /** Quita meta name="cover" y properties="cover-image" que apunten a una portada que vamos a sustituir. */
    private static String removeCoverDeclarations(String opf) {
        String result = opf.replaceAll("<(?:[\\w-]+:)?meta\\b[^>]*\\bname=\"cover\"[^>]*/>\\s*", "");
        result = result.replaceAll("(<(?:[\\w-]+:)?item\\b[^>]*\\bproperties=\")([^\"]*)\\bcover-image\\b\\s*([^\"]*\")", "$1$2$3");
        return result.replace(" properties=\"\"", "");
    }

    private static String uniqueId(Document opfDoc) {
        java.util.Set<String> ids = new java.util.HashSet<>();
        NodeList all = opfDoc.getElementsByTagNameNS("*", "*");
        for (int i = 0; i < all.getLength(); i++) {
            ids.add(((Element) all.item(i)).getAttribute("id"));
        }
        String id = NEW_COVER_ID;
        for (int n = 2; ids.contains(id); n++) {
            id = NEW_COVER_ID + "-" + n;
        }
        return id;
    }

    private static String uniqueHref(ZipFile zip, String opfDir, String name) {
        String href = name;
        for (int n = 2; zip.getEntry(opfDir + href) != null; n++) {
            href = name.replace(".jpg", "-" + n + ".jpg");
        }
        return href;
    }

    // --- Paginas de cubierta en SVG --------------------------------------------

    /**
     * Las cubiertas tipo Calibre/Sigil son un SVG con viewBox="0 0 ancho alto" y un
     * &lt;image width height&gt;. Si no se actualizan, la imagen nueva se estira.
     */
    private static void fixSvgCoverPages(ZipFile zip, String coverPath, BufferedImage image,
            Map<String, byte[]> replacements) throws IOException {
        String coverName = coverPath.substring(coverPath.lastIndexOf('/') + 1);
        String w = String.valueOf(image.getWidth());
        String h = String.valueOf(image.getHeight());

        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            String name = entry.getName().toLowerCase(Locale.ROOT);
            if (entry.isDirectory() || !(name.endsWith(".xhtml") || name.endsWith(".html") || name.endsWith(".htm"))) {
                continue;
            }
            String text = EpubZip.readText(zip, entry.getName());
            if (!text.contains("<svg")) {
                continue;
            }

            // Solo los <svg> cuyo <image> apunta exactamente a la portada (href="...cover.jpg"),
            // no a otra imagen que contenga ese nombre (1.jpg vs 11.jpg)
            Pattern refersToCover = Pattern.compile("href=\"(?:[^\"]*/)?" + Pattern.quote(coverName) + "\"");
            Matcher svg = SVG_BLOCK.matcher(text);
            StringBuilder out = new StringBuilder();
            while (svg.find()) {
                String block = svg.group();
                if (refersToCover.matcher(block).find()) {
                    block = SVG_VIEWBOX.matcher(block).replaceFirst("$10 0 " + w + " " + h + "$2");
                    Matcher img = IMAGE_TAG.matcher(block);
                    StringBuilder sb = new StringBuilder();
                    while (img.find()) {
                        String tag = img.group();
                        if (refersToCover.matcher(tag).find()) {
                            tag = WIDTH_ATTR.matcher(tag).replaceAll("$1" + w + "$2");
                            tag = HEIGHT_ATTR.matcher(tag).replaceAll("$1" + h + "$2");
                        }
                        img.appendReplacement(sb, Matcher.quoteReplacement(tag));
                    }
                    img.appendTail(sb);
                    block = sb.toString();
                }
                svg.appendReplacement(out, Matcher.quoteReplacement(block));
            }
            svg.appendTail(out);
            String updated = out.toString();

            if (!updated.equals(text)) {
                replacements.put(entry.getName(), updated.getBytes(StandardCharsets.UTF_8));
            }
        }
    }
}
