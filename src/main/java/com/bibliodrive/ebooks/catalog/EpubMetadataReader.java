package com.bibliodrive.ebooks.catalog;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Lee titulo, autor y portada directamente del metadata interno (content.opf) de un epub,
 * sin depender de librerias externas.
 */
public class EpubMetadataReader {

    public record CoverImage(byte[] data, String contentType) {
    }

    /** {@code series}/{@code seriesIndex} son null si el epub no declara saga. */
    public record EpubMetadata(String title, String author, String series, String seriesIndex, CoverImage cover) {
        static final EpubMetadata EMPTY = new EpubMetadata(null, null, null, null, null);
    }

    record Series(String name, String index) {
    }

    public EpubMetadata read(Path epubFile) {
        try (ZipFile zip = new ZipFile(epubFile.toFile())) {
            String opfPath = findOpfPath(zip);
            if (opfPath == null) {
                return EpubMetadata.EMPTY;
            }
            Document opfDoc = parseXml(zip, opfPath);
            String title = firstText(opfDoc, "title");
            String author = firstText(opfDoc, "creator");
            Series series = readSeries(opfDoc);
            CoverImage cover = findCover(zip, opfDoc, opfPath);
            return new EpubMetadata(title, author,
                    series == null ? null : series.name(), series == null ? null : series.index(), cover);
        } catch (Exception e) {
            return EpubMetadata.EMPTY;
        }
    }

    String findOpfPath(ZipFile zip) throws Exception {
        ZipEntry container = zip.getEntry("META-INF/container.xml");
        if (container == null) {
            return null;
        }
        Document doc = parseXml(zip.getInputStream(container));
        NodeList rootfiles = doc.getElementsByTagNameNS("*", "rootfile");
        if (rootfiles.getLength() == 0) {
            return null;
        }
        Element rootfile = (Element) rootfiles.item(0);
        String path = rootfile.getAttribute("full-path");
        return path.isBlank() ? null : path;
    }

    Document parseXml(ZipFile zip, String entryPath) throws Exception {
        ZipEntry entry = zip.getEntry(entryPath);
        if (entry == null) {
            throw new FileNotFoundException(entryPath);
        }
        return parseXml(zip.getInputStream(entry));
    }

    private Document parseXml(InputStream in) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        return builder.parse(in);
    }

    private static final String DC_NS = "http://purl.org/dc/elements/1.1/";

    private String firstText(Document doc, String dcLocalName) {
        NodeList nodes = doc.getElementsByTagNameNS(DC_NS, dcLocalName);
        if (nodes.getLength() == 0) {
            return null;
        }
        String text = nodes.item(0).getTextContent();
        return text == null ? null : text.trim();
    }

    /**
     * Saga del libro: primero la de Calibre (meta name="calibre:series"), que es la
     * que escribe esta app; si no, la de EPUB 3 (meta property="belongs-to-collection").
     */
    Series readSeries(Document opfDoc) {
        NodeList metas = opfDoc.getElementsByTagNameNS("*", "meta");
        String calibreName = null;
        String calibreIndex = null;
        for (int i = 0; i < metas.getLength(); i++) {
            Element meta = (Element) metas.item(i);
            String name = meta.getAttribute("name");
            if ("calibre:series".equals(name)) {
                calibreName = meta.getAttribute("content").trim();
            } else if ("calibre:series_index".equals(name)) {
                calibreIndex = meta.getAttribute("content").trim();
            }
        }
        if (calibreName != null && !calibreName.isEmpty()) {
            return new Series(calibreName, normalizeIndex(calibreIndex));
        }

        for (int i = 0; i < metas.getLength(); i++) {
            Element meta = (Element) metas.item(i);
            if ("belongs-to-collection".equals(meta.getAttribute("property"))) {
                String name = meta.getTextContent() == null ? "" : meta.getTextContent().trim();
                if (name.isEmpty()) {
                    continue;
                }
                String index = null;
                String id = meta.getAttribute("id");
                for (int j = 0; j < metas.getLength() && !id.isEmpty(); j++) {
                    Element refine = (Element) metas.item(j);
                    if (("#" + id).equals(refine.getAttribute("refines"))
                            && "group-position".equals(refine.getAttribute("property"))) {
                        index = refine.getTextContent();
                    }
                }
                return new Series(name, normalizeIndex(index));
            }
        }
        return null;
    }

    /** "1.0" -> "1", "2.5" -> "2.5"; null o no numerico -> null. */
    static String normalizeIndex(String index) {
        if (index == null || index.isBlank()) {
            return null;
        }
        try {
            java.math.BigDecimal value = new java.math.BigDecimal(index.trim()).stripTrailingZeros();
            return value.signum() <= 0 ? null : value.toPlainString();
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Donde esta la portada dentro del epub. {@code declared} indica si el OPF la
     * marca como portada (properties="cover-image" o meta name="cover"); si es
     * false se encontro solo por el nombre del fichero.
     */
    public record CoverLocation(String itemId, String zipPath, boolean declared) {
    }

    private CoverImage findCover(ZipFile zip, Document opfDoc, String opfPath) {
        try {
            CoverLocation location = locateCover(opfDoc, opfPath);
            if (location == null) {
                return null;
            }
            ZipEntry coverEntry = zip.getEntry(location.zipPath());
            if (coverEntry == null) {
                return null;
            }
            byte[] data = zip.getInputStream(coverEntry).readAllBytes();
            return new CoverImage(data, guessContentType(location.zipPath()));
        } catch (Exception e) {
            return null;
        }
    }

    CoverLocation locateCover(Document opfDoc, String opfPath) {
        String opfDir = opfDirOf(opfPath);

        // id -> {href, properties}, en el orden del manifiesto
        Map<String, String[]> manifest = new LinkedHashMap<>();
        NodeList items = opfDoc.getElementsByTagNameNS("*", "item");
        for (int i = 0; i < items.getLength(); i++) {
            Element item = (Element) items.item(i);
            manifest.put(item.getAttribute("id"),
                    new String[]{item.getAttribute("href"), item.getAttribute("properties")});
        }

        for (Map.Entry<String, String[]> e : manifest.entrySet()) {
            String properties = e.getValue()[1];
            if (properties != null && properties.contains("cover-image")) {
                return new CoverLocation(e.getKey(), resolveRelative(opfDir, e.getValue()[0]), true);
            }
        }

        NodeList metas = opfDoc.getElementsByTagNameNS("*", "meta");
        for (int i = 0; i < metas.getLength(); i++) {
            Element meta = (Element) metas.item(i);
            if ("cover".equals(meta.getAttribute("name"))) {
                String content = meta.getAttribute("content");
                String[] entry = manifest.get(content);
                if (entry != null) {
                    return new CoverLocation(content, resolveRelative(opfDir, entry[0]), true);
                }
                break;
            }
        }

        // Ultimo recurso (EPUBs de Sigil sin metadato de portada): una
        // imagen del manifiesto llamada "cover"/"portada".
        for (Map.Entry<String, String[]> e : manifest.entrySet()) {
            String href = e.getValue()[0];
            String name = href.substring(href.lastIndexOf('/') + 1).toLowerCase(java.util.Locale.ROOT);
            if ((name.contains("cover") || name.contains("portada"))
                    && name.matches(".*\\.(jpe?g|png|gif|webp)$")) {
                return new CoverLocation(e.getKey(), resolveRelative(opfDir, href), false);
            }
        }
        return null;
    }

    static String opfDirOf(String opfPath) {
        return opfPath.contains("/") ? opfPath.substring(0, opfPath.lastIndexOf('/') + 1) : "";
    }

    static String resolveRelative(String baseDir, String href) {
        if (href.startsWith("/")) {
            return href.substring(1);
        }
        return (baseDir + href).replace("\\", "/");
    }

    private String guessContentType(String path) {
        String lower = path.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        return "image/jpeg";
    }
}
