package com.bibliodrive.ebooks.security;

import com.bibliodrive.ebooks.log.AccessLog;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.http.HttpStatus;

import java.util.List;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public DaoAuthenticationProvider localAuthenticationProvider(
            LocalUserDetailsService userDetailsService, PasswordEncoder encoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(encoder);
        return provider;
    }

    /**
     * Primero intenta contra el NAS; si esa cuenta no existe o el NAS falla,
     * cae a la lista de usuarios locales.
     */
    @Bean
    public AuthenticationManager authenticationManager(
            DsmAuthenticationProvider dsmProvider, DaoAuthenticationProvider localAuthenticationProvider) {
        return new ProviderManager(List.of(dsmProvider, localAuthenticationProvider));
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, AuthenticationManager authenticationManager,
            LoginAttemptService loginAttempts, DsmGroups groups, AccessLog accessLog) throws Exception {
        RequestMatcher api = request -> request.getRequestURI().startsWith("/api/");
        http
                .authenticationManager(authenticationManager)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/login.html", "/styles.css", "/theme.js", "/fonts/**", "/icon.svg").permitAll()
                        // Solo desde el propio NAS. Lo que entra por el tunel trae la IP real
                        // (forward-headers native) y cabeceras de Cloudflare, asi que no cuela.
                        .requestMatchers("/internal/**").access((authentication, context) ->
                                new AuthorizationDecision(isLocalRequest(context.getRequest())))
                        // Lo que cambia la biblioteca, solo los administradores
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PATCH, "/api/books/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/books/*/cover", "/api/books/*/cover/**",
                                "/api/authors/rename", "/api/series/rename", "/api/index/refresh").hasRole("ADMIN")
                        .requestMatchers("/api/covers/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .addFilterBefore(new LoginThrottleFilter(loginAttempts),
                        org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(new GroupRolesFilter(groups), AuthorizationFilter.class)
                // La API responde 401 (la interfaz lleva al login); el resto redirige
                .exceptionHandling(e -> e
                        .defaultAuthenticationEntryPointFor(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED), api)
                        .defaultAuthenticationEntryPointFor(new LoginUrlAuthenticationEntryPoint("/login.html"),
                                request -> true))
                .formLogin(form -> form
                        .loginPage("/login.html")
                        .loginProcessingUrl("/login")
                        .successHandler((request, response, auth) -> {
                            loginAttempts.loginSucceeded(request.getRemoteAddr(), auth.getName());
                            accessLog.event(AccessLog.Kind.LOGIN, auth.getName(), request.getRemoteAddr(),
                                    "Inicio de sesión", userAgent(request));
                            response.sendRedirect("/");
                        })
                        .failureHandler((request, response, ex) -> {
                            String user = request.getParameter("username");
                            if (ex instanceof DisabledException) {
                                accessLog.event(AccessLog.Kind.LOGIN_DENIED, user, request.getRemoteAddr(),
                                        "Contraseña correcta, pero la cuenta no está en los grupos de BiblioLocal");
                                response.sendRedirect("/login.html?noaccess");
                                return;
                            }
                            loginAttempts.loginFailed(request.getRemoteAddr(), user);
                            accessLog.event(AccessLog.Kind.LOGIN_FAILED, user, request.getRemoteAddr(),
                                    "Usuario o contraseña incorrectos");
                            response.sendRedirect("/login.html?error");
                        })
                        .permitAll())
                .logout(logout -> logout
                        .logoutUrl("/logout")
                        .addLogoutHandler((request, response, auth) -> {
                            if (auth != null) {
                                accessLog.event(AccessLog.Kind.LOGOUT, auth.getName(), request.getRemoteAddr(),
                                        "Cierre de sesión");
                            }
                        })
                        .logoutSuccessUrl("/login.html")
                        .permitAll())
                // Sin CSRF: la cookie de sesion va con SameSite=Lax (application.properties),
                // asi que el navegador no la manda en POST lanzados desde otras webs.
                .csrf(csrf -> csrf.disable());
        return http.build();
    }

    private static String userAgent(HttpServletRequest request) {
        String ua = request.getHeader("User-Agent");
        return ua == null ? null : ua;
    }

    private static boolean isLocalRequest(HttpServletRequest request) {
        String ip = request.getRemoteAddr();
        boolean loopback = "127.0.0.1".equals(ip) || "0:0:0:0:0:0:0:1".equals(ip) || "::1".equals(ip);
        return loopback && request.getHeader("Cf-Connecting-IP") == null && request.getHeader("Cf-Ray") == null;
    }
}
