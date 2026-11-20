package com.bibliodrive.ebooks.reading;

import com.bibliodrive.ebooks.store.JsonStore;
import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Lo que lleva leido cada persona: por donde va en cada libro (la posicion del
 * lector, una CFI de epub, y el porcentaje) y cuales ha terminado. En
 * {@code data/reading.json}, para seguir en el movil donde se dejo en el PC.
 */
@Service
public class Reading {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Entry {
        /** Posicion en el epub (EPUB CFI); null si solo se ha marcado como leido. */
        public String cfi;
        public double percent;
        public long updatedAt;
        /** Cuando lo termino; null si no esta leido. */
        public Long finishedAt;
    }

    public static class Data {
        /** usuario → id del libro → lectura */
        public Map<String, Map<String, Entry>> users = new LinkedHashMap<>();
    }

    private final JsonStore<Data> store;

    public Reading(@Value("${app.data-dir:data}") String dataDir) {
        this.store = new JsonStore<>(Path.of(dataDir, "reading.json"), Data.class, Data::new);
    }

    public synchronized Map<String, Entry> of(String user) {
        return Map.copyOf(store.data().users.getOrDefault(key(user), Map.of()));
    }

    /** Guarda por donde va. Un CFI absurdo o demasiado largo no se acepta. */
    public synchronized Entry progress(String user, String bookId, String cfi, double percent) {
        if (cfi == null || cfi.length() > 1000 || !cfi.startsWith("epubcfi(")) {
            throw new IllegalArgumentException("Posición no válida");
        }
        Entry e = entry(user, bookId);
        e.cfi = cfi;
        e.percent = Math.max(0, Math.min(1, Double.isFinite(percent) ? percent : 0));
        e.updatedAt = System.currentTimeMillis();
        store.save();
        return e;
    }

    /** Marcar o desmarcar como leido. Devuelve true si ha cambiado. */
    public synchronized boolean setRead(String user, String bookId, boolean read) {
        Entry e = entry(user, bookId);
        if (read == (e.finishedAt != null)) return false;
        e.finishedAt = read ? System.currentTimeMillis() : null;
        e.updatedAt = System.currentTimeMillis();
        if (read) e.percent = Math.max(e.percent, 1);
        store.save();
        return true;
    }

    private Entry entry(String user, String bookId) {
        if (bookId == null || !bookId.matches("[0-9a-f]{40}")) throw new IllegalArgumentException("Libro no válido");
        return store.data().users.computeIfAbsent(key(user), k -> new LinkedHashMap<>())
                .computeIfAbsent(bookId, k -> new Entry());
    }

    private static String key(String user) {
        return user.trim().toLowerCase(Locale.ROOT);
    }
}
