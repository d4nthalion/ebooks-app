package com.bibliodrive.ebooks.catalog;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.HashMap;
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

    public record EpubMetadata(String title, String author, CoverImage cover) {
        static final EpubMetadata EMPTY = new EpubMetadata(null, null, null);
    }

    public EpubMetadata read(Path epubFile) {
        try (ZipFile zip = new ZipFile(epubFile.toFile())) {
            String opfPath = findOpfPath(zip);
            if (opfPath == null) {
                return EpubMetadata.EMPTY;
            }
            Document opfDoc = parseXml(zip, opfPath);
            String title = firstText(opfDoc, "dc:title");
            String author = firstText(opfDoc, "dc:creator");
            CoverImage cover = findCover(zip, opfDoc, opfPath);
            return new EpubMetadata(title, author, cover);
        } catch (Exception e) {
            return EpubMetadata.EMPTY;
        }
    }

    private String findOpfPath(ZipFile zip) throws Exception {
        ZipEntry container = zip.getEntry("META-INF/container.xml");
        if (container == null) {
            return null;
        }
        Document doc = parseXml(zip.getInputStream(container));
        NodeList rootfiles = doc.getElementsByTagName("rootfile");
        if (rootfiles.getLength() == 0) {
            return null;
        }
        Element rootfile = (Element) rootfiles.item(0);
        String path = rootfile.getAttribute("full-path");
        return path.isBlank() ? null : path;
    }

    private Document parseXml(ZipFile zip, String entryPath) throws Exception {
        ZipEntry entry = zip.getEntry(entryPath);
        if (entry == null) {
            throw new FileNotFoundException(entryPath);
        }
        return parseXml(zip.getInputStream(entry));
    }

    private Document parseXml(InputStream in) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        DocumentBuilder builder = factory.newDocumentBuilder();
        return builder.parse(in);
    }

    private String firstText(Document doc, String tagName) {
        NodeList nodes = doc.getElementsByTagName(tagName);
        if (nodes.getLength() == 0) {
            return null;
        }
        String text = nodes.item(0).getTextContent();
        return text == null ? null : text.trim();
    }

    private CoverImage findCover(ZipFile zip, Document opfDoc, String opfPath) {
        try {
            String opfDir = opfPath.contains("/") ? opfPath.substring(0, opfPath.lastIndexOf('/') + 1) : "";

            Map<String, String[]> manifest = new HashMap<>();
            NodeList items = opfDoc.getElementsByTagName("item");
            for (int i = 0; i < items.getLength(); i++) {
                Element item = (Element) items.item(i);
                manifest.put(item.getAttribute("id"),
                        new String[]{item.getAttribute("href"), item.getAttribute("properties")});
            }

            String coverHref = null;

            for (String[] value : manifest.values()) {
                if (value[1] != null && value[1].contains("cover-image")) {
                    coverHref = value[0];
                    break;
                }
            }

            if (coverHref == null) {
                NodeList metas = opfDoc.getElementsByTagName("meta");
                for (int i = 0; i < metas.getLength(); i++) {
                    Element meta = (Element) metas.item(i);
                    if ("cover".equals(meta.getAttribute("name"))) {
                        String[] entry = manifest.get(meta.getAttribute("content"));
                        if (entry != null) {
                            coverHref = entry[0];
                        }
                        break;
                    }
                }
            }

            // Ultimo recurso (EPUBs de Sigil sin metadato de portada): una
            // imagen del manifiesto llamada "cover"/"portada".
            if (coverHref == null) {
                for (String[] value : manifest.values()) {
                    String name = value[0].substring(value[0].lastIndexOf('/') + 1).toLowerCase(java.util.Locale.ROOT);
                    if ((name.contains("cover") || name.contains("portada"))
                            && name.matches(".*\\.(jpe?g|png|gif|webp)$")) {
                        coverHref = value[0];
                        break;
                    }
                }
            }

            if (coverHref == null) {
                return null;
            }

            String coverPath = resolveRelative(opfDir, coverHref);
            ZipEntry coverEntry = zip.getEntry(coverPath);
            if (coverEntry == null) {
                return null;
            }

            byte[] data = zip.getInputStream(coverEntry).readAllBytes();
            return new CoverImage(data, guessContentType(coverPath));
        } catch (Exception e) {
            return null;
        }
    }

    private String resolveRelative(String baseDir, String href) {
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
