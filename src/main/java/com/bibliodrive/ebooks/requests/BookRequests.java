package com.bibliodrive.ebooks.requests;

import com.bibliodrive.ebooks.catalog.Book;
import com.bibliodrive.ebooks.catalog.CatalogIndexService;
import com.bibliodrive.ebooks.catalog.Names;
import com.bibliodrive.ebooks.log.AccessLog;
import com.bibliodrive.ebooks.store.JsonStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;

/**
 * Peticiones: libros que los usuarios piden y que todavia no estan en la
 * biblioteca. Una peticion por libro: si otra persona pide lo mismo, se suma a
 * la misma. Los administradores la marcan como conseguida o rechazada (con una
 * respuesta); al aparecer el libro en la biblioteca se marca sola.
 */
@Service
public class BookRequests {

    public static final String PENDING = "pending";
    public static final String DONE = "done";
    public static final String REJECTED = "rejected";
    private static final Set<String> STATUSES = Set.of(PENDING, DONE, REJECTED);
    private static final int MAX_PENDING_PER_USER = 50;
    /** Las resueltas se guardan un tiempo para que quien las pidio vea que paso. */
    private static final long KEEP_RESOLVED_MS = 60L * 24 * 3600 * 1000;

    public static class Requester {
        public String name;
        public long at;
        public String note;
        /** Hasta cuando ha visto esta persona los cambios de la peticion (para avisarle). */
        public long seenAt;
    }

    public static class Request {
        public String id;
        public String key;
        public String olKey;
        public String title;
        public String author;
        public Integer year;
        public Long coverId;
        /** Saga y numero, si se pidio desde el hueco de una saga. */
        public String series;
        public String seriesIndex;
        public List<Requester> users = new ArrayList<>();
        public String status = PENDING;
        public long at;
        public Long resolvedAt;
        public String resolvedBy;
        public String reply;
        public String bookId;
    }

    public static class Data {
        public List<Request> items = new ArrayList<>();
    }

    /** Lo que manda la interfaz al pedir un libro. */
    public record NewRequest(String olKey, String title, String author, Integer year, Long coverId,
            String series, String seriesIndex, String note) {
    }

    private final JsonStore<Data> store;
    private final CatalogIndexService catalog;
    private final AccessLog accessLog;
    private final SecureRandom random = new SecureRandom();

    public BookRequests(@Value("${app.data-dir:data}") String dataDir, CatalogIndexService catalog,
            AccessLog accessLog) {
        this.store = new JsonStore<>(Path.of(dataDir, "requests.json"), Data.class, Data::new);
        this.catalog = catalog;
        this.accessLog = accessLog;
        prune();
        matchLibrary();
    }

    private List<Request> items() {
        return store.data().items;
    }

    public synchronized List<Request> list(String user, boolean all) {
        String name = keyOf(user);
        return items().stream().filter(r -> all || hasUser(r, name)).toList();
    }

    public synchronized Optional<Request> find(String id) {
        return items().stream().filter(r -> r.id.equals(id)).findFirst();
    }

    public synchronized long pendingCount() {
        return items().stream().filter(r -> PENDING.equals(r.status)).count();
    }

    /** Peticiones propias resueltas que esta persona aun no ha visto. */
    public synchronized List<Request> unseen(String user) {
        String name = keyOf(user);
        return items().stream()
                .filter(r -> r.resolvedAt != null)
                .filter(r -> r.users.stream().anyMatch(u -> u.name.equals(name) && u.seenAt < r.resolvedAt))
                .toList();
    }

    public synchronized void markSeen(String user) {
        String name = keyOf(user);
        long now = System.currentTimeMillis();
        boolean changed = false;
        for (Request r : items()) {
            for (Requester u : r.users) {
                if (u.name.equals(name) && r.resolvedAt != null && u.seenAt < r.resolvedAt) {
                    u.seenAt = now;
                    changed = true;
                }
            }
        }
        if (changed) store.save();
    }

    public synchronized Request create(String user, NewRequest entry, String ip) {
        String name = keyOf(user);
        String title = trim(entry.title(), 200);
        String author = trim(entry.author(), 200);
        if (title == null) throw new IllegalArgumentException("Falta el título");
        if (author == null) throw new IllegalArgumentException("Falta el autor");
        if (inLibrary(title, author).isPresent()) {
            throw new IllegalArgumentException("Ese libro ya está en la biblioteca");
        }
        String olKey = entry.olKey() != null && entry.olKey().matches("/works/OL\\d+W") ? entry.olKey() : null;
        String key = olKey != null ? olKey : "t:" + Names.titleKey(title) + "|a:" + Names.key(author);
        String note = trim(entry.note(), 300);

        Request r = items().stream().filter(x -> x.key.equals(key)).findFirst().orElse(null);
        if (r != null && hasUser(r, name) && PENDING.equals(r.status)) return r;
        long mine = items().stream().filter(x -> PENDING.equals(x.status) && hasUser(x, name)).count();
        if (mine >= MAX_PENDING_PER_USER) {
            throw new IllegalArgumentException("Ya tienes " + MAX_PENDING_PER_USER + " peticiones pendientes");
        }
        long now = System.currentTimeMillis();
        if (r == null) {
            r = new Request();
            byte[] id = new byte[8];
            random.nextBytes(id);
            r.id = HexFormat.of().formatHex(id);
            r.key = key;
            r.olKey = olKey;
            r.title = title;
            r.author = author;
            r.year = entry.year() != null && entry.year() > 1000 && entry.year() < 3000 ? entry.year() : null;
            r.coverId = entry.coverId() != null && entry.coverId() > 0 ? entry.coverId() : null;
            r.series = trim(entry.series(), 200);
            r.seriesIndex = entry.seriesIndex() != null && entry.seriesIndex().matches("\\d{1,3}([.,]\\d{1,2})?")
                    ? entry.seriesIndex().replace(',', '.') : null;
            r.at = now;
            items().add(0, r);
        } else if (!PENDING.equals(r.status)) {
            // Se vuelve a pedir algo rechazado: otra vez pendiente
            r.status = PENDING;
            r.at = now;
            r.resolvedAt = null;
            r.resolvedBy = null;
            r.reply = null;
            r.users.removeIf(u -> u.name.equals(name));
        }
        Requester who = new Requester();
        who.name = name;
        who.at = now;
        who.note = note;
        r.users.add(who);
        store.save();
        accessLog.event(AccessLog.Kind.REQUEST, user, ip, "Pide «" + r.title + "» de " + r.author, note);
        return r;
    }

    /** Quitar la peticion propia (si nadie mas la ha pedido, desaparece). */
    public synchronized void cancel(String user, String id, String ip) {
        String name = keyOf(user);
        Request r = find(id).filter(x -> hasUser(x, name)).orElseThrow(NoSuchElementException::new);
        r.users.removeIf(u -> u.name.equals(name));
        if (r.users.isEmpty()) items().remove(r);
        store.save();
        accessLog.event(AccessLog.Kind.REQUEST, user, ip, "Retira su petición de «" + r.title + "»");
    }

    /** Administrador: conseguida, rechazada o de nuevo pendiente, con una respuesta opcional. */
    public synchronized Request resolve(String admin, String id, String status, String reply, String ip) {
        Request r = find(id).orElseThrow(NoSuchElementException::new);
        if (!STATUSES.contains(status)) throw new IllegalArgumentException("Estado no válido");
        r.status = status;
        r.reply = trim(reply, 300);
        r.resolvedAt = PENDING.equals(status) ? null : System.currentTimeMillis();
        r.resolvedBy = PENDING.equals(status) ? null : admin;
        if (DONE.equals(status) && r.bookId == null) {
            inLibrary(r.title, r.author).ifPresent(b -> r.bookId = b.id());
        }
        store.save();
        accessLog.event(AccessLog.Kind.REQUEST_RESOLVED, admin, ip,
                statusText(status) + ": «" + r.title + "»", r.reply);
        return r;
    }

    /** Lo ha colocado una descarga de aMule. */
    public synchronized void fulfilled(String id, Book book, String by) {
        find(id).ifPresent(r -> {
            if (!PENDING.equals(r.status)) return;
            r.status = DONE;
            r.resolvedAt = System.currentTimeMillis();
            r.resolvedBy = by;
            r.bookId = book.id();
            store.save();
        });
    }

    /** Lo pedido que ya esta en la biblioteca se da por conseguido. */
    @EventListener
    public void onRefresh(CatalogIndexService.RefreshedEvent event) {
        matchLibrary();
    }

    public synchronized void matchLibrary() {
        boolean changed = false;
        for (Request r : items()) {
            if (!PENDING.equals(r.status)) continue;
            Optional<Book> book = inLibrary(r.title, r.author);
            if (book.isPresent()) {
                r.status = DONE;
                r.resolvedAt = System.currentTimeMillis();
                r.bookId = book.get().id();
                changed = true;
            }
        }
        if (changed) store.save();
    }

    /** El libro de la biblioteca con ese titulo y autor, si lo hay. */
    public Optional<Book> inLibrary(String title, String author) {
        String t = Names.titleKey(title);
        if (t.isEmpty()) return Optional.empty();
        return catalog.findAll(null, null, null).stream()
                .filter(b -> Names.titleKey(b.title()).equals(t) && Names.sameAuthor(b.author(), author))
                .findFirst();
    }

    private void prune() {
        long limit = System.currentTimeMillis() - KEEP_RESOLVED_MS;
        if (items().removeIf(r -> !PENDING.equals(r.status) && (r.resolvedAt != null ? r.resolvedAt : r.at) < limit)) {
            store.save();
        }
    }

    private static boolean hasUser(Request r, String name) {
        return r.users.stream().anyMatch(u -> u.name.equals(name));
    }

    private static String statusText(String status) {
        return switch (status) {
            case DONE -> "Conseguida";
            case REJECTED -> "Rechazada";
            default -> "Otra vez pendiente";
        };
    }

    static String keyOf(String user) {
        return user == null ? "" : user.trim().toLowerCase(Locale.ROOT);
    }

    private static String trim(String value, int max) {
        if (value == null || value.isBlank()) return null;
        String v = value.trim();
        return v.length() > max ? v.substring(0, max) : v;
    }
}
