package com.bibliodrive.ebooks.catalog;

import java.text.Normalizer;
import java.util.Locale;

/** Comparar y escribir nombres de libros y autores. */
public final class Names {

    private Names() {
    }

    /**
     * Clave para comparar: sin tildes, mayusculas, signos ni espacios dobles.
     * «J. R. R. Tolkien» y «J.R.R. Tolkien» dan lo mismo: "jrrtolkien".
     */
    public static String key(String value) {
        if (value == null) return "";
        String plain = Normalizer.normalize(value, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return plain.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "");
    }

    /**
     * Mismo autor, aunque uno venga como «Apellido, Nombre» (lo hace Calibre a veces)
     * o con segundo nombre abreviado.
     */
    public static boolean sameAuthor(String a, String b) {
        if (key(a).isEmpty() || key(b).isEmpty()) return false;
        return key(a).equals(key(b)) || key(flip(a)).equals(key(b)) || key(a).equals(key(flip(b)));
    }

    private static String flip(String name) {
        int comma = name.indexOf(',');
        return comma < 0 ? name : name.substring(comma + 1).trim() + " " + name.substring(0, comma).trim();
    }

    /**
     * Titulo comparable: sin subtitulo ni lo que va entre parentesis
     * («Dune (Edición ilustrada)» = «Dune»).
     */
    public static String titleKey(String title) {
        if (title == null) return "";
        String t = title.replaceAll("\\(.*?\\)|\\[.*?\\]", " ");
        // «Juramentada / Oathbringer»: el titulo en español, sin el original
        int slash = t.indexOf(" / ");
        if (slash > 0) t = t.substring(0, slash);
        int colon = t.indexOf(':');
        if (colon > 3) t = t.substring(0, colon);
        return key(t.replaceFirst("(?i)^\\s*(el|la|los|las|the)\\s+", ""));
    }

    /** Nombre de archivo o carpeta valido en el NAS y en Windows: «Dune: Mesías» → «Dune - Mesías». */
    public static String safeFileName(String value) {
        String s = value == null ? "" : value;
        s = s.replaceAll("\\s*:\\s*", " - ")
                .replaceAll("[<>\"/\\\\|?*\\x00-\\x1f]", " ")
                .replaceAll("\\s+", " ")
                .replaceAll("[. ]+$", "")
                .trim();
        return s.length() > 150 ? s.substring(0, 150).trim() : s;
    }

    /** «CAMINO DE LOS REYES» → «Camino de los reyes» (Open Library trae ediciones en mayusculas). */
    public static String tidyCase(String value) {
        if (value == null || value.isBlank()) return value;
        boolean shouting = value.equals(value.toUpperCase(Locale.ROOT)) && value.matches(".*\\p{L}{3,}.*");
        if (!shouting) return value.trim();
        String lower = value.trim().toLowerCase(Locale.forLanguageTag("es"));
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }
}
