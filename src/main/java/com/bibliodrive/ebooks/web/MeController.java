package com.bibliodrive.ebooks.web;

import com.bibliodrive.ebooks.amule.AmuleService;
import com.bibliodrive.ebooks.requests.BookRequests;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/** Quien soy y que tengo pendiente de ver: lo primero que pide la interfaz. */
@RestController
@RequestMapping("/api")
public class MeController {

    private final BookRequests requests;
    private final AmuleService amule;

    public MeController(BookRequests requests, AmuleService amule) {
        this.requests = requests;
        this.amule = amule;
    }

    @GetMapping("/me")
    public Map<String, Object> me(Authentication auth) {
        boolean admin = isAdmin(auth);
        Map<String, Object> me = new LinkedHashMap<>();
        me.put("username", auth.getName());
        me.put("admin", admin);
        // Peticiones propias resueltas desde la ultima vez que las vio
        me.put("updates", requests.unseen(auth.getName()).stream()
                .map(r -> Map.of("id", r.id, "title", r.title, "status", r.status,
                        "bookId", r.bookId == null ? "" : r.bookId))
                .toList());
        if (admin) {
            me.put("pendingRequests", requests.pendingCount());
            me.put("amule", amule.enabled());
            me.put("activeDownloads", amule.snapshot().downloads().size());
        }
        return me;
    }

    static boolean isAdmin(Authentication auth) {
        return auth != null && auth.getAuthorities().stream().anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
    }
}
