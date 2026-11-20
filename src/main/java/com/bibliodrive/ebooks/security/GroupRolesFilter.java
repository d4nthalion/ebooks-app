package com.bibliodrive.ebooks.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Las sesiones de cuentas del NAS duran mientras el navegador siga abierto, pero
 * el rol se mira en cada peticion contra los grupos de DSM (leer /etc/group es
 * barato: solo se relee si cambia). Asi, quitar a alguien del grupo o hacerle
 * administrador vale al momento, sin esperar a que vuelva a iniciar sesion.
 */
public class GroupRolesFilter extends OncePerRequestFilter {

    private final DsmGroups groups;

    public GroupRolesFilter(DsmGroups groups) {
        this.groups = groups;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && hasAuthority(auth, DsmAuthenticationProvider.DSM_ACCOUNT)) {
            DsmGroups.Role role = groups.roleOf(auth.getName());
            if (role == DsmGroups.Role.NONE) {
                HttpSession session = request.getSession(false);
                if (session != null) session.invalidate();
                SecurityContextHolder.clearContext();
                if (request.getRequestURI().startsWith("/api/")) {
                    response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
                } else {
                    response.sendRedirect("/login.html?noaccess");
                }
                return;
            }
            boolean admin = role == DsmGroups.Role.ADMIN;
            if (admin != hasAuthority(auth, "ROLE_ADMIN")) {
                // Solo para esta peticion: la siguiente se vuelve a calcular igual
                List<GrantedAuthority> authorities = DsmAuthenticationProvider.authoritiesFor(role);
                var updated = UsernamePasswordAuthenticationToken.authenticated(auth.getPrincipal(), null, authorities);
                var context = SecurityContextHolder.createEmptyContext();
                context.setAuthentication(updated);
                SecurityContextHolder.setContext(context);
            }
        }
        chain.doFilter(request, response);
    }

    static boolean hasAuthority(Authentication auth, String authority) {
        return auth.getAuthorities().stream().anyMatch(a -> authority.equals(a.getAuthority()));
    }
}
