package com.bibliodrive.ebooks.catalog;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

/**
 * Cambia titulo, autor y saga dentro del OPF de un epub.
 *
 * Se edita el texto del OPF solo en las etiquetas afectadas (no se reserializa el
 * XML entero) para no tocar nada mas del libro. La saga se guarda como la de
 * Calibre (meta name="calibre:series" / "calibre:series_index"), que es la que
 * entienden Calibre, KOReader y compania; si el epub trae una coleccion EPUB 3
 * (belongs-to-collection) se actualiza tambien.
 */
public class EpubMetadataWriter {

    /**
     * Cambios a aplicar. Un campo null significa "no tocar". Para la saga, una
     * cadena vacia la quita del libro.
     */
    public record Changes(String title, String author, String series, String seriesIndex) {
        public boolean isEmpty() {
            return title == null && author == null && series == null && seriesIndex == null;
        }
    }

    private static final String P = "(?:[\\w-]+:)?";

    private final EpubMetadataReader reader = new EpubMetadataReader();

    /** @return true si se ha modificado el epub, false si ya tenia esos datos */
    public boolean update(Path epub, Changes changes) throws IOException {
        ZipFile zip = new ZipFile(epub.toFile(), StandardCharsets.UTF_8);
        Map<String, byte[]> replacements;
        try {
            String opfPath;
            try {
                opfPath = reader.findOpfPath(zip);
            } catch (Exception e) {
                throw new IllegalArgumentException("No se pudo leer el epub: " + e.getMessage(), e);
            }
            if (opfPath == null) {
                throw new IllegalArgumentException("El epub no tiene META-INF/container.xml valido");
            }
            String opf = EpubZip.readText(zip, opfPath);
            String updated = apply(opf, changes);
            if (updated.equals(opf)) {
                zip.close();
                return false;
            }
            replacements = Map.of(opfPath, updated.getBytes(StandardCharsets.UTF_8));
        } catch (RuntimeException | IOException e) {
            zip.close();
            throw e;
        }
        EpubZip.rewrite(zip, epub, replacements);
        return true;
    }

    /** Aplica los cambios sobre el texto del OPF (separado para poder probarlo). */
    static String apply(String opf, Changes changes) {
        String result = opf;
        if (changes.title() != null) {
            result = setDcElement(result, "title", changes.title());
        }
        if (changes.author() != null) {
            result = setAuthor(result, changes.author());
        }
        if (changes.series() != null || changes.seriesIndex() != null) {
            result = setSeries(result, changes.series(), changes.seriesIndex());
        }
        return result;
    }

    // --- dc:title / dc:creator ------------------------------------------------------

    private static Pattern dcElement(String localName) {
        return Pattern.compile("<(" + P + ")" + localName + "\\b([^>]*)>(.*?)</\\1" + localName + "\\s*>",
                Pattern.DOTALL);
    }

    private static String setDcElement(String opf, String localName, String value) {
        Matcher m = dcElement(localName).matcher(opf);
        String escaped = EpubZip.xmlEscape(value.trim());
        if (m.find()) {
            String open = "<" + m.group(1) + localName + m.group(2) + ">";
            return opf.substring(0, m.start()) + open + escaped + "</" + m.group(1) + localName + ">"
                    + opf.substring(m.end());
        }
        return EpubZip.insertBefore(opf, "metadata",
                "<" + dcPrefix(opf) + localName + ">" + escaped + "</" + dcPrefix(opf) + localName + ">");
    }

    /**
     * Cambia el primer autor. Se quita su "file-as" (orden tipo "Tolkien, J. R. R."),
     * que si no seguiria diciendo el nombre antiguo en Calibre.
     */
    private static String setAuthor(String opf, String author) {
        Matcher m = dcElement("creator").matcher(opf);
        if (!m.find()) {
            return setDcElement(opf, "creator", author);
        }
        String attrs = m.group(2).replaceAll("\\s+" + P + "file-as=\"[^\"]*\"", "");
        String open = "<" + m.group(1) + "creator" + attrs + ">";
        String result = opf.substring(0, m.start()) + open + EpubZip.xmlEscape(author.trim())
                + "</" + m.group(1) + "creator>" + opf.substring(m.end());

        Matcher id = Pattern.compile("\\bid=\"([^\"]+)\"").matcher(attrs);
        if (id.find()) {
            result = removeRefines(result, id.group(1), "file-as");
        }
        return result;
    }

    private static String dcPrefix(String opf) {
        Matcher m = Pattern.compile("xmlns:([\\w-]+)=\"http://purl\\.org/dc/elements/1\\.1/\"").matcher(opf);
        return m.find() ? m.group(1) + ":" : "dc:";
    }

    // --- Saga -----------------------------------------------------------------------

    private static String setSeries(String opf, String series, String seriesIndex) {
        String result = opf;
        // Nombre ya escapado para XML. Si se conserva el actual se copia tal cual,
        // sin desescapar, para no estropear entidades como &#241;.
        String nameXml;
        if (series != null) {
            nameXml = EpubZip.xmlEscape(series.trim());
        } else {
            Matcher current = metaByName("calibre:series").matcher(result);
            nameXml = current.find() ? attr(current.group(), "content") : null;
            if (nameXml == null) {
                Matcher collection = collectionMeta().matcher(result);
                nameXml = collection.find() ? collection.group(3).trim().replace("\"", "&quot;") : null;
            }
            if (nameXml == null || nameXml.isBlank()) {
                return result;
            }
            nameXml = nameXml.trim();
        }
        String index = seriesIndex == null ? null : seriesIndex.trim();
        if (index == null) {
            Matcher currentIndex = metaByName("calibre:series_index").matcher(result);
            index = currentIndex.find() ? attr(currentIndex.group(), "content") : null;
        }
        if (index == null) {
            // EPUB 3 de Calibre: el numero solo esta en el refine group-position de la coleccion
            Matcher collection = collectionMeta().matcher(result);
            String id = collection.find() ? attr(collection.group(2), "id") : null;
            if (id != null) {
                Matcher position = Pattern.compile("<(" + P + ")meta\\b[^>]*\\brefines=\"#" + Pattern.quote(id)
                        + "\"[^>]*\\bproperty=\"group-position\"[^>]*>(.*?)</\\1meta\\s*>", Pattern.DOTALL)
                        .matcher(result);
                index = position.find() ? position.group(2).trim() : null;
            }
        }

        result = metaByName("calibre:series").matcher(result).replaceAll("");
        result = metaByName("calibre:series_index").matcher(result).replaceAll("");

        // Coleccion EPUB 3: se actualiza el nombre (o se quita junto a sus refines)
        Matcher collection = collectionMeta().matcher(result);
        if (collection.find()) {
            String id = attr(collection.group(2), "id");
            if (nameXml.isEmpty()) {
                result = result.substring(0, collection.start()) + result.substring(collection.end());
                if (id != null) {
                    result = removeRefines(result, id, null);
                }
            } else {
                result = result.substring(0, collection.start())
                        + "<" + collection.group(1) + "meta" + collection.group(2) + ">" + nameXml
                        + "</" + collection.group(1) + "meta>" + result.substring(collection.end());
                if (id != null) {
                    result = removeRefines(result, id, "group-position");
                    if (index != null && !index.isEmpty()) {
                        result = EpubZip.insertBefore(result, "metadata", "<meta refines=\"#" + id
                                + "\" property=\"group-position\">" + EpubZip.xmlEscape(index) + "</meta>");
                    }
                }
            }
        }

        if (!nameXml.isEmpty()) {
            result = EpubZip.insertBefore(result, "metadata",
                    "<meta name=\"calibre:series\" content=\"" + nameXml + "\"/>");
            if (index != null && !index.isEmpty()) {
                result = EpubZip.insertBefore(result, "metadata",
                        "<meta name=\"calibre:series_index\" content=\"" + EpubZip.xmlEscape(index) + "\"/>");
            }
        }
        return result;
    }

    /** meta con name="..." (autocerrado o con &lt;/meta&gt;) y el espacio que le siga. */
    private static Pattern metaByName(String name) {
        return Pattern.compile("<(" + P + ")meta\\b[^>]*\\bname=\"" + Pattern.quote(name)
                + "\"[^>]*?(?:/>|>\\s*</\\1meta\\s*>)[ \\t]*\\r?\\n?[ \\t]*");
    }

    /** grupo 1: prefijo, grupo 2: atributos, grupo 3: texto. */
    private static Pattern collectionMeta() {
        return Pattern.compile("<(" + P + ")meta(\\b[^>]*\\bproperty=\"belongs-to-collection\"[^>]*)>(.*?)</\\1meta\\s*>",
                Pattern.DOTALL);
    }

    /** Quita los meta refines="#id" (de una propiedad concreta, o todos si property es null). */
    private static String removeRefines(String opf, String id, String property) {
        Pattern p = Pattern.compile("<(" + P + ")meta\\b[^>]*\\brefines=\"#" + Pattern.quote(id) + "\"[^>]*>.*?</\\1meta\\s*>[ \\t]*\\r?\\n?[ \\t]*",
                Pattern.DOTALL);
        Matcher m = p.matcher(opf);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            boolean matches = property == null || m.group().contains("property=\"" + property + "\"");
            m.appendReplacement(sb, matches ? "" : Matcher.quoteReplacement(m.group()));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String attr(String tag, String name) {
        Matcher m = Pattern.compile("\\b" + Pattern.quote(name) + "=\"([^\"]*)\"").matcher(tag);
        return m.find() ? m.group(1) : null;
    }
}
