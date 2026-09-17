package com.bibliodrive.ebooks.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/**
 * Valida usuario/contrasena contra las cuentas del propio NAS Synology, usando
 * la API de login de DSM (auth.cgi). No mantiene la sesion de DSM abierta: solo
 * se usa como comprobacion puntual de credenciales ("passthrough").
 *
 * Si el NAS no responde o las credenciales no son validas alli, se lanza
 * BadCredentialsException (no InternalAuthenticationServiceException) a proposito,
 * para que el ProviderManager de Spring Security siga probando con el siguiente
 * proveedor (usuarios locales) en vez de cortar la autenticacion en seco.
 */
@Component
public class DsmAuthenticationProvider implements AuthenticationProvider {

    private static final Logger log = LoggerFactory.getLogger(DsmAuthenticationProvider.class);

    private final String baseUrl;
    private final HttpClient httpClient;
    private final ObjectMapper mapper = new ObjectMapper();

    public DsmAuthenticationProvider(
            @Value("${dsm.base-url}") String baseUrl,
            @Value("${dsm.connect-timeout-ms:3000}") long connectTimeoutMs) {
        this.baseUrl = baseUrl;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .build();
    }

    @Override
    public Authentication authenticate(Authentication authentication) throws AuthenticationException {
        String username = authentication.getName();
        String password = String.valueOf(authentication.getCredentials());

        String sid = login(username, password);
        if (sid == null) {
            throw new BadCredentialsException("Credenciales de NAS no validas para " + username);
        }
        logout(sid);

        return new UsernamePasswordAuthenticationToken(username, password,
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }

    private String login(String username, String password) {
        try {
            String url = baseUrl + "/webapi/auth.cgi"
                    + "?api=SYNO.API.Auth&version=6&method=login"
                    + "&account=" + encode(username)
                    + "&passwd=" + encode(password)
                    + "&session=EbooksApi&format=sid";
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).GET().build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode json = mapper.readTree(response.body());
            if (json.path("success").asBoolean(false)) {
                return json.path("data").path("sid").asText(null);
            }
            log.info("Login DSM rechazado para {}: {}", username, json.path("error").toString());
            return null;
        } catch (Exception e) {
            log.warn("No se pudo contactar con el NAS para autenticar a {}: {}", username, e.toString());
            return null;
        }
    }

    private void logout(String sid) {
        try {
            String url = baseUrl + "/webapi/auth.cgi?api=SYNO.API.Auth&version=6&method=logout"
                    + "&session=EbooksApi&_sid=" + sid;
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).GET().build();
            httpClient.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            log.debug("No se pudo cerrar la sesion de DSM: {}", e.toString());
        }
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication);
    }
}
