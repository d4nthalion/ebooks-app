package com.bibliodrive.ebooks.universes;

import com.bibliodrive.ebooks.catalog.Names;
import com.bibliodrive.ebooks.log.AccessLog;
import com.bibliodrive.ebooks.store.JsonStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * Universos: sagas de sagas (el Cosmere, la Tierra Media, el Circulo del
 * Mundo...). Cada uno agrupa sagas enteras y, ademas, libros sueltos que
 * pertenecen a ese mundo sin ser de ninguna saga (Elantris en el Cosmere).
 *
 * <p>No va en los epub, que no tienen donde guardarlo: se guarda en
 * {@code data/universes.json}. Las sagas se apuntan por nombre (comparado sin
 * tildes ni mayusculas) y los libros sueltos por su id.
 */
@Service
public class Universes {

    public static class Universe {
        public String id;
        public String name;
        public List<String> sagas = new ArrayList<>();
        public List<String> books = new ArrayList<>();
    }

    public static class Data {
        public List<Universe> items = new ArrayList<>();
    }

    public record Change(String name, List<String> sagas, List<String> books) {
    }

    private final JsonStore<Data> store;
    private final AccessLog accessLog;
    private final SecureRandom random = new SecureRandom();

    /** Empieza vacio: los universos los crea y edita un administrador, nunca salen de las carpetas. */
    public Universes(@Value("${app.data-dir:data}") String dataDir, AccessLog accessLog) {
        this.store = new JsonStore<>(Path.of(dataDir, "universes.json"), Data.class, Data::new);
        this.accessLog = accessLog;
    }

    public synchronized List<Universe> list() {
        return store.data().items.stream()
                .sorted((a, b) -> a.name.compareToIgnoreCase(b.name))
                .toList();
    }

    public synchronized Universe create(Change change, String user, String ip) {
        Universe u = new Universe();
        byte[] id = new byte[6];
        random.nextBytes(id);
        u.id = HexFormat.of().formatHex(id);
        apply(u, change);
        store.data().items.add(u);
        store.save();
        accessLog.event(AccessLog.Kind.UNIVERSE, user, ip, "Universo creado: " + u.name, summary(u));
        return u;
    }

    public synchronized Universe update(String id, Change change, String user, String ip) {
        Universe u = find(id);
        String before = u.name;
        apply(u, change);
        store.save();
        accessLog.event(AccessLog.Kind.UNIVERSE, user, ip,
                "Universo cambiado: " + (before.equals(u.name) ? u.name : before + " → " + u.name), summary(u));
        return u;
    }

    public synchronized void delete(String id, String user, String ip) {
        Universe u = find(id);
        store.data().items.remove(u);
        store.save();
        accessLog.event(AccessLog.Kind.UNIVERSE, user, ip, "Universo borrado: " + u.name);
    }

    private Universe find(String id) {
        return store.data().items.stream().filter(u -> u.id.equals(id)).findFirst()
                .orElseThrow(NoSuchElementException::new);
    }

    private void apply(Universe u, Change change) {
        String name = change.name() == null ? "" : change.name().trim();
        if (name.isEmpty()) throw new IllegalArgumentException("Ponle un nombre al universo");
        if (name.length() > 120) throw new IllegalArgumentException("El nombre es demasiado largo");
        boolean taken = store.data().items.stream()
                .anyMatch(o -> o != u && Names.key(o.name).equals(Names.key(name)));
        if (taken) throw new IllegalArgumentException("Ya hay un universo que se llama así");
        u.name = name;
        u.sagas = dedupe(change.sagas(), true);
        u.books = dedupe(change.books(), false);
    }

    private static List<String> dedupe(List<String> values, boolean byName) {
        Map<String, String> seen = new LinkedHashMap<>();
        if (values != null) {
            for (String v : values) {
                if (v == null || v.isBlank() || v.length() > 200) continue;
                seen.putIfAbsent(byName ? Names.key(v) : v.trim(), v.trim());
            }
        }
        return new ArrayList<>(seen.values());
    }

    private static String summary(Universe u) {
        return u.sagas.size() + " sagas, " + u.books.size() + " libros sueltos";
    }
}
