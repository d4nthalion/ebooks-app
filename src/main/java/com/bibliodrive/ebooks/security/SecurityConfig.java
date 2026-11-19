package com.bibliodrive.ebooks.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

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
            LoginAttemptService loginAttempts) throws Exception {
        http
                .authenticationManager(authenticationManager)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/login.html", "/styles.css", "/theme.js", "/fonts/**").permitAll()
                        // Solo desde el propio NAS. Lo que entra por el tunel trae la IP real
                        // (forward-headers native) y cabeceras de Cloudflare, asi que no cuela.
                        .requestMatchers("/internal/**").access((authentication, context) ->
                                new AuthorizationDecision(isLocalRequest(context.getRequest())))
                        .anyRequest().authenticated())
                .addFilterBefore(new LoginThrottleFilter(loginAttempts), UsernamePasswordAuthenticationFilter.class)
                .formLogin(form -> form
                        .loginPage("/login.html")
                        .loginProcessingUrl("/login")
                        .successHandler((request, response, auth) -> {
                            loginAttempts.loginSucceeded(request.getRemoteAddr(), auth.getName());
                            response.sendRedirect("/");
                        })
                        .failureHandler((request, response, ex) -> {
                            loginAttempts.loginFailed(request.getRemoteAddr(), request.getParameter("username"));
                            response.sendRedirect("/login.html?error");
                        })
                        .permitAll())
                .logout(logout -> logout
                        .logoutUrl("/logout")
                        .logoutSuccessUrl("/login.html")
                        .permitAll())
                // Sin CSRF: la cookie de sesion va con SameSite=Lax (application.properties),
                // asi que el navegador no la manda en POST lanzados desde otras webs.
                .csrf(csrf -> csrf.disable());
        return http.build();
    }

    private static boolean isLocalRequest(HttpServletRequest request) {
        String ip = request.getRemoteAddr();
        boolean loopback = "127.0.0.1".equals(ip) || "0:0:0:0:0:0:0:1".equals(ip) || "::1".equals(ip);
        return loopback && request.getHeader("Cf-Connecting-IP") == null && request.getHeader("Cf-Ray") == null;
    }
}
