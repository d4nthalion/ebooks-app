package com.bibliodrive.ebooks.security;

import com.bibliodrive.ebooks.log.AccessLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Limita los intentos de login fallidos, porque la app esta publicada en
 * internet y el bloqueo automatico de DSM no sirve aqui: DSM solo ve llegar
 * los intentos desde localhost (es la app quien llama a auth.cgi), nunca la IP
 * real del atacante.
 *
 * Se cuentan fallos por IP (frena a un atacante concreto) y por usuario (frena
 * ataques repartidos entre muchas IPs contra la misma cuenta). En memoria, sin
 * base de datos: un reinicio de la app limpia los contadores.
 */
@Service
public class LoginAttemptService {

    private static final Logger log = LoggerFactory.getLogger(LoginAttemptService.class);
    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private record Attempts(int failures, Instant windowStart, Instant blockedUntil) {
    }

    private final int maxPerIp;
    private final int maxPerUser;
    private final Duration window;
    private final Duration blockTime;
    private final Map<String, Attempts> byIp = new ConcurrentHashMap<>();
    private final Map<String, Attempts> byUser = new ConcurrentHashMap<>();
    private final AccessLog accessLog;

    /** Un bloqueo activo, para el panel de administracion. kind: ip | user */
    public record Block(String kind, String key, long until) {
    }

    public LoginAttemptService(
            @Value("${app.login.max-failures-per-ip:5}") int maxPerIp,
            @Value("${app.login.max-failures-per-user:10}") int maxPerUser,
            @Value("${app.login.window-minutes:15}") long windowMinutes,
            @Value("${app.login.block-minutes:15}") long blockMinutes,
            AccessLog accessLog) {
        this.accessLog = accessLog;
        this.maxPerIp = maxPerIp;
        this.maxPerUser = maxPerUser;
        this.window = Duration.ofMinutes(windowMinutes);
        this.blockTime = Duration.ofMinutes(blockMinutes);
    }

    public boolean isBlocked(String ip, String username) {
        Instant now = Instant.now();
        return blocked(byIp.get(ip), now) || (username != null && blocked(byUser.get(key(username)), now));
    }

    public void loginFailed(String ip, String username) {
        if (record(byIp, ip, maxPerIp)) {
            log.warn("IP {} bloqueada {} min por {} logins fallidos", ip, blockTime.toMinutes(), maxPerIp);
            accessLog.event(AccessLog.Kind.BLOCKED, null, ip,
                    "IP bloqueada " + blockTime.toMinutes() + " min tras " + maxPerIp + " intentos fallidos");
        }
        if (username != null && !username.isBlank() && record(byUser, key(username), maxPerUser)) {
            log.warn("Usuario {} bloqueado {} min por {} logins fallidos", username, blockTime.toMinutes(), maxPerUser);
            accessLog.event(AccessLog.Kind.BLOCKED, username, ip,
                    "Cuenta bloqueada " + blockTime.toMinutes() + " min tras " + maxPerUser + " intentos fallidos");
        }
    }

    public List<Block> blocks() {
        Instant now = Instant.now();
        List<Block> result = new ArrayList<>();
        byIp.forEach((ip, a) -> {
            if (blocked(a, now)) result.add(new Block("ip", ip, a.blockedUntil().toEpochMilli()));
        });
        byUser.forEach((user, a) -> {
            if (blocked(a, now)) result.add(new Block("user", user, a.blockedUntil().toEpochMilli()));
        });
        return result;
    }

    public void loginSucceeded(String ip, String username) {
        byIp.remove(ip);
        if (username != null) {
            byUser.remove(key(username));
        }
    }

    /** IPs y usuarios bloqueados ahora mismo, con la hora a la que se desbloquean. */
    public List<String> currentBlocks() {
        Instant now = Instant.now();
        List<String> lines = new ArrayList<>();
        byIp.forEach((ip, a) -> {
            if (blocked(a, now)) lines.add("ip      " + ip + "  hasta las " + HOUR.format(a.blockedUntil()));
        });
        byUser.forEach((user, a) -> {
            if (blocked(a, now)) lines.add("usuario " + user + "  hasta las " + HOUR.format(a.blockedUntil()));
        });
        return lines;
    }

    /** Borra los fallos de esa IP o usuario (lo que coincida). Devuelve si habia algo. */
    public boolean unblock(String ipOrUsername) {
        boolean ip = byIp.remove(ipOrUsername.trim()) != null;
        boolean user = byUser.remove(key(ipOrUsername)) != null;
        if (ip || user) {
            log.info("Desbloqueado a mano: {}", ipOrUsername);
        }
        return ip || user;
    }

    /** Suma un fallo; devuelve true si con este fallo se acaba de bloquear. */
    private boolean record(Map<String, Attempts> map, String k, int max) {
        Instant now = Instant.now();
        Attempts updated = map.compute(k, (key, a) -> {
            if (a == null || a.windowStart().plus(window).isBefore(now)) {
                a = new Attempts(0, now, null);
            }
            int failures = a.failures() + 1;
            Instant blockedUntil = failures >= max ? now.plus(blockTime) : a.blockedUntil();
            return new Attempts(failures, a.windowStart(), blockedUntil);
        });
        return updated.failures() == max;
    }

    private boolean blocked(Attempts a, Instant now) {
        return a != null && a.blockedUntil() != null && a.blockedUntil().isAfter(now);
    }

    private String key(String username) {
        return username.trim().toLowerCase(Locale.ROOT);
    }
}
