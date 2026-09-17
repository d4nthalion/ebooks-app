package com.bibliodrive.ebooks.user;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Preferencias sencillas por usuario (de momento, solo el email de Kindle).
 * Igual que local-users.json: un archivo JSON plano, sin base de datos.
 */
@Service
public class UserPreferencesService {

    private static final Logger log = LoggerFactory.getLogger(UserPreferencesService.class);

    private final Path file;
    private final ObjectMapper mapper = new ObjectMapper();

    public UserPreferencesService(@Value("${app.kindle-emails-file}") String file) {
        this.file = Path.of(file);
    }

    public synchronized String getKindleEmail(String username) {
        return readAll().get(username.toLowerCase(Locale.ROOT));
    }

    public synchronized void setKindleEmail(String username, String email) {
        Map<String, String> all = readAll();
        if (email == null || email.isBlank()) {
            all.remove(username.toLowerCase(Locale.ROOT));
        } else {
            all.put(username.toLowerCase(Locale.ROOT), email.trim());
        }
        writeAll(all);
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> readAll() {
        if (!Files.exists(file)) {
            return new HashMap<>();
        }
        try {
            return new HashMap<>(mapper.readValue(file.toFile(), Map.class));
        } catch (IOException e) {
            log.error("Error leyendo {}", file, e);
            return new HashMap<>();
        }
    }

    private void writeAll(Map<String, String> all) {
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), all);
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo guardar " + file, e);
        }
    }
}
