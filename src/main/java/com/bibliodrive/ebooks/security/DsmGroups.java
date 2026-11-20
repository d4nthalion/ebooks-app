package com.bibliodrive.ebooks.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Quien entra y quien administra, segun dos grupos de DSM (Panel de control >
 * Grupo): {@code bibliolocal-admin} y {@code bibliolocal-user}. La app corre en
 * el propio NAS, asi que lee los miembros directamente de {@code /etc/group}
 * (DSM lo reescribe al cambiar un grupo: se vuelve a leer cuando cambia).
 *
 * <p>En cuanto existe alguno de los dos grupos, quien no este en ninguno no
 * entra. Mientras no existan, entra cualquier cuenta del NAS y administran las
 * de {@code app.admins}.
 */
@Service
public class DsmGroups {

    private static final Logger log = LoggerFactory.getLogger(DsmGroups.class);

    public enum Role { ADMIN, USER, NONE }

    private final Path groupFile;
    private final String adminGroup;
    private final String userGroup;
    private final Set<String> fallbackAdmins;

    private long loadedModified = -1;
    private Map<String, Set<String>> groups = Map.of();

    public DsmGroups(
            @Value("${app.groups-file:/etc/group}") String groupFile,
            @Value("${app.admin-group:bibliolocal-admin}") String adminGroup,
            @Value("${app.user-group:bibliolocal-user}") String userGroup,
            @Value("${app.admins:}") String fallbackAdmins) {
        this.groupFile = Path.of(groupFile);
        this.adminGroup = adminGroup;
        this.userGroup = userGroup;
        this.fallbackAdmins = Arrays.stream(fallbackAdmins.split(","))
                .map(s -> s.trim().toLowerCase(Locale.ROOT))
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }

    public synchronized Role roleOf(String username) {
        String user = username.trim().toLowerCase(Locale.ROOT);
        Map<String, Set<String>> current = current();
        Set<String> admins = current.get(adminGroup);
        Set<String> users = current.get(userGroup);
        if (admins == null && users == null) {
            return fallbackAdmins.contains(user) ? Role.ADMIN : Role.USER;
        }
        if (admins != null && admins.contains(user)) return Role.ADMIN;
        if (users != null && users.contains(user)) return Role.USER;
        return Role.NONE;
    }

    /** Los grupos de /etc/group, releidos solo si el archivo ha cambiado. */
    private Map<String, Set<String>> current() {
        try {
            long modified = Files.getLastModifiedTime(groupFile).toMillis();
            if (modified != loadedModified) {
                groups = parse(Files.readAllLines(groupFile, StandardCharsets.UTF_8));
                loadedModified = modified;
            }
        } catch (IOException e) {
            if (loadedModified != -2) {
                log.warn("No se pueden leer los grupos de DSM en {}: {}", groupFile, e.getMessage());
                loadedModified = -2;
            }
        }
        return groups;
    }

    /** {@code nombre:x:gid:miembro1,miembro2} */
    static Map<String, Set<String>> parse(Iterable<String> lines) {
        Map<String, Set<String>> result = new HashMap<>();
        for (String line : lines) {
            String[] parts = line.split(":", -1);
            if (parts.length < 4) continue;
            Set<String> members = Arrays.stream(parts[3].split(","))
                    .map(s -> s.trim().toLowerCase(Locale.ROOT))
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toSet());
            result.put(parts[0], members);
        }
        return result;
    }
}
