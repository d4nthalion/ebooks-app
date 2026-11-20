package com.bibliodrive.ebooks.universes;

import com.bibliodrive.ebooks.catalog.Names;
import com.bibliodrive.ebooks.log.AccessLog;
import com.bibliodrive.ebooks.store.JsonStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Orden a mano de los libros sin numero dentro de su saga. No se escribe en el
 * epub (seria inventarle un numero): cada uno guarda una posicion decimal entre
 * los numerados (2,5 = entre el 2 y el 3) en {@code data/saga-order.json}.
 * Solo vale mientras el libro siga en esa saga.
 */
@Service
public class SagaOrder {

    public static class Entry {
        /** Clave de la saga (sin tildes ni mayusculas) en la que se ordeno. */
        public String saga;
        public double pos;
    }

    public static class Data {
        public Map<String, Entry> items = new LinkedHashMap<>();
    }

    private final JsonStore<Data> store;
    private final AccessLog accessLog;

    public SagaOrder(@Value("${app.data-dir:data}") String dataDir, AccessLog accessLog) {
        this.store = new JsonStore<>(Path.of(dataDir, "saga-order.json"), Data.class, Data::new);
        this.accessLog = accessLog;
    }

    /** id del libro → { saga, pos } */
    public synchronized Map<String, Entry> all() {
        return Map.copyOf(store.data().items);
    }

    /**
     * Fija las posiciones de los libros sin numero de una saga (los que no vengan
     * dejan de tener posicion en ella).
     */
    public synchronized void set(String sagaName, Map<String, Double> positions, String user, String ip) {
        if (sagaName == null || sagaName.isBlank()) throw new IllegalArgumentException("Falta la saga");
        String saga = Names.key(sagaName);
        store.data().items.entrySet().removeIf(e -> saga.equals(e.getValue().saga));
        positions.forEach((id, pos) -> {
            if (id == null || !id.matches("[0-9a-f]{40}") || pos == null || pos.isNaN() || pos.isInfinite()) {
                throw new IllegalArgumentException("Orden no válido");
            }
            Entry entry = new Entry();
            entry.saga = saga;
            entry.pos = pos;
            store.data().items.put(id, entry);
        });
        store.save();
        accessLog.event(AccessLog.Kind.EDIT, user, ip, "Orden de la saga «" + sagaName.trim() + "» cambiado",
                positions.size() + " libros sin número colocados a mano");
    }
}
