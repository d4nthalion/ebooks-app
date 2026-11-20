package com.bibliodrive.ebooks.store;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.function.Supplier;

/**
 * Un archivo JSON con el estado de un servicio (peticiones, universos, descargas...).
 * Sin base de datos, como el resto de la app: se lee al arrancar y se guarda
 * entero en cada cambio, a un .tmp que luego se renombra, para que un corte a
 * medias nunca deje el archivo a trozos.
 */
public class JsonStore<T> {

    private static final Logger log = LoggerFactory.getLogger(JsonStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final Path file;
    private final JavaType type;
    private T data;

    public JsonStore(Path file, Class<T> type, Supplier<T> empty) {
        this.file = file;
        this.type = MAPPER.getTypeFactory().constructType(type);
        this.data = load(empty);
    }

    /** true si el archivo no existia al arrancar (para sembrar datos iniciales). */
    public boolean isNew() {
        return !Files.exists(file);
    }

    public synchronized T data() {
        return data;
    }

    public synchronized void save() {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), data);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo guardar " + file, e);
        }
    }

    private T load(Supplier<T> empty) {
        if (!Files.exists(file)) {
            return empty.get();
        }
        try {
            T value = MAPPER.readValue(file.toFile(), type);
            return value == null ? empty.get() : value;
        } catch (IOException e) {
            // Mejor arrancar con el archivo apartado que perderlo al guardar encima
            Path broken = file.resolveSibling(file.getFileName() + ".roto-" + System.currentTimeMillis());
            log.error("{} no se puede leer; se aparta como {} y se empieza de cero", file, broken, e);
            try {
                Files.move(file, broken);
            } catch (IOException ignored) {
                // si ni eso se puede, al menos que arranque
            }
            return empty.get();
        }
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }
}
