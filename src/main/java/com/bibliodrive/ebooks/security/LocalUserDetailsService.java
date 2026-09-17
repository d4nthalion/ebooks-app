package com.bibliodrive.ebooks.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Usuarios que no existen en el NAS: se guardan en un archivo JSON sencillo
 * (sin base de datos), pensado para un puñado de cuentas (familia/invitados).
 * Las contrasenas se guardan como hash BCrypt, nunca en texto plano.
 */
@Service
public class LocalUserDetailsService implements UserDetailsService {

    private static final Logger log = LoggerFactory.getLogger(LocalUserDetailsService.class);

    private final Path usersFile;
    private final ObjectMapper mapper = new ObjectMapper();

    public LocalUserDetailsService(@Value("${app.local-users-file}") String usersFile) {
        this.usersFile = Path.of(usersFile);
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        LocalUser user = readUsers().get(username.toLowerCase(Locale.ROOT));
        if (user == null) {
            throw new UsernameNotFoundException(username);
        }
        List<SimpleGrantedAuthority> authorities = user.roles().stream()
                .map(SimpleGrantedAuthority::new)
                .collect(Collectors.toList());
        return new User(user.username(), user.passwordHash(), authorities);
    }

    private Map<String, LocalUser> readUsers() {
        if (!Files.exists(usersFile)) {
            return Map.of();
        }
        try {
            List<LocalUser> list = mapper.readValue(usersFile.toFile(),
                    mapper.getTypeFactory().constructCollectionType(List.class, LocalUser.class));
            return list.stream().collect(Collectors.toMap(u -> u.username().toLowerCase(Locale.ROOT), u -> u));
        } catch (IOException e) {
            log.error("Error leyendo el archivo de usuarios locales {}", usersFile, e);
            return Map.of();
        }
    }
}
