package com.bibliodrive.ebooks.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Corta el POST /login antes de validar credenciales si la IP o el usuario
 * estan bloqueados, para que un atacante bloqueado ni siquiera llegue a DSM.
 *
 * La IP es request.getRemoteAddr(): con server.forward-headers-strategy=native
 * Tomcat la sustituye por la IP real que manda el tunel de Cloudflare, pero
 * solo cuando la peticion viene de localhost (ver internal-proxies).
 */
public class LoginThrottleFilter extends OncePerRequestFilter {

    private final LoginAttemptService attempts;

    public LoginThrottleFilter(LoginAttemptService attempts) {
        this.attempts = attempts;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !("POST".equals(request.getMethod()) && "/login".equals(request.getServletPath()));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (attempts.isBlocked(request.getRemoteAddr(), request.getParameter("username"))) {
            response.sendRedirect("/login.html?blocked");
            return;
        }
        chain.doFilter(request, response);
    }
}
