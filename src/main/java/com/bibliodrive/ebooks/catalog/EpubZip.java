package com.bibliodrive.ebooks.catalog;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** Utilidades para leer y reescribir entradas de un epub (que es un zip). */
final class EpubZip {

    private EpubZip() {
    }

    static String readText(ZipFile zip, String entryName) throws IOException {
        ZipEntry entry = zip.getEntry(entryName);
        if (entry == null) {
            throw new IOException("No existe " + entryName + " en el epub");
        }
        String text = new String(zip.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8);
        return text.startsWith("﻿") ? text.substring(1) : text;
    }

    /**
     * Reescribe {@code epub} cambiando (o anadiendo) las entradas de {@code replacements}.
     * Se escribe a un temporal oculto en la misma carpeta (extension .tmp, el indice lo
     * ignora) y despues se mueve encima del original: nunca queda un epub a medias.
     *
     * @param zip el epub ya abierto; se cierra aqui antes de mover el fichero
     */
    static void rewrite(ZipFile zip, Path epub, Map<String, byte[]> replacements) throws IOException {
        Path temp = epub.resolveSibling("." + epub.getFileName() + ".tmp");
        try {
            try (zip) {
                writeZip(zip, replacements, temp);
            }
            Files.move(temp, epub, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /** El "mimetype" va primero y sin comprimir, como exige la especificacion EPUB. */
    private static void writeZip(ZipFile zip, Map<String, byte[]> replacements, Path target) throws IOException {
        Map<String, byte[]> pending = new LinkedHashMap<>(replacements);
        try (OutputStream fileOut = Files.newOutputStream(target);
             ZipOutputStream out = new ZipOutputStream(fileOut, StandardCharsets.UTF_8)) {

            Set<String> written = new HashSet<>();
            ZipEntry mimetype = zip.getEntry("mimetype");
            if (mimetype != null) {
                writeStored(out, "mimetype", zip.getInputStream(mimetype).readAllBytes());
                written.add("mimetype");
                pending.remove("mimetype");
            }

            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!written.add(entry.getName())) {
                    continue;
                }
                ZipEntry copy = new ZipEntry(entry.getName());
                if (entry.getLastModifiedTime() != null) {
                    copy.setLastModifiedTime(entry.getLastModifiedTime());
                }
                out.putNextEntry(copy);
                if (!entry.isDirectory()) {
                    byte[] replacement = pending.remove(entry.getName());
                    if (replacement != null) {
                        out.write(replacement);
                    } else {
                        try (var in = zip.getInputStream(entry)) {
                            in.transferTo(out);
                        }
                    }
                }
                out.closeEntry();
            }

            for (Map.Entry<String, byte[]> e : pending.entrySet()) {
                out.putNextEntry(new ZipEntry(e.getKey()));
                out.write(e.getValue());
                out.closeEntry();
            }
        }
    }

    private static void writeStored(ZipOutputStream out, String name, byte[] data) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setMethod(ZipEntry.STORED);
        entry.setSize(data.length);
        entry.setCompressedSize(data.length);
        CRC32 crc = new CRC32();
        crc.update(data);
        entry.setCrc(crc.getValue());
        out.putNextEntry(entry);
        out.write(data);
        out.closeEntry();
    }

    /** Inserta {@code snippet} justo antes de la etiqueta de cierre (con o sin prefijo, p.ej. opf:). */
    static String insertBefore(String opf, String element, String snippet) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("</(?:[\\w-]+:)?" + element + "\\s*>").matcher(opf);
        if (!m.find()) {
            throw new IllegalArgumentException("El OPF no tiene <" + element + ">");
        }
        return opf.substring(0, m.start()) + "  " + snippet + "\n  " + opf.substring(m.start());
    }

    /** Escapa texto para meterlo en un elemento o atributo XML. */
    static String xmlEscape(String text) {
        // Caracteres de control prohibidos en XML 1.0 (p.ej. pegados desde otra web)
        return text.replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]", "").replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
