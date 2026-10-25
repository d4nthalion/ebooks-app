package com.bibliodrive.ebooks.security;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Administracion de los bloqueos de login desde el propio NAS
 * (ebooks-api.sh blocked|unblock). Solo accesible desde localhost: ver
 * SecurityConfig, y el tunel de Cloudflare tampoco enruta /internal.
 */
@RestController
@RequestMapping("/internal/login-blocks")
public class LoginBlocksController {

    private final LoginAttemptService attempts;

    public LoginBlocksController(LoginAttemptService attempts) {
        this.attempts = attempts;
    }

    @GetMapping(produces = "text/plain;charset=UTF-8")
    public String list() {
        var blocks = attempts.currentBlocks();
        return blocks.isEmpty() ? "No hay nadie bloqueado.\n" : String.join("\n", blocks) + "\n";
    }

    @PostMapping(value = "/unblock", produces = "text/plain;charset=UTF-8")
    public ResponseEntity<String> unblock(@RequestParam String key) {
        return attempts.unblock(key)
                ? ResponseEntity.ok("Desbloqueado: " + key + "\n")
                : ResponseEntity.status(404).body("No habia bloqueos para " + key + "\n");
    }
}
