package com.bibliodrive.ebooks.amule;

import com.bibliodrive.ebooks.catalog.Book;
import com.bibliodrive.ebooks.catalog.CatalogIndexService;
import com.bibliodrive.ebooks.log.AccessLog;
import com.bibliodrive.ebooks.requests.BookRequests;
import com.bibliodrive.ebooks.store.JsonStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Descargas de libros con el aMule del NAS (el mismo que usa CineLocal, que ya no
 * toca los ebooks). Consulta aMule, recuerda para que peticion es cada descarga
 * y, al terminar, coloca el libro en la biblioteca y da la peticion por
 * conseguida. Lo que no se puede identificar queda «pendiente de colocar».
 *
 * <p>Solo se tocan los ebooks (epub, mobi, azw3, pdf...) que BiblioLocal ha
 * lanzado o ha visto descargandose: nunca lo que ya estaba terminado.
 */
@Service
public class AmuleService {

    private static final Logger log = LoggerFactory.getLogger(AmuleService.class);
    public static final Pattern EBOOK = Pattern.compile("(?i).+\\.(epub|mobi|azw3?|pdf|fb2|cbz|cbr)$");
    private static final Set<String> LIBRARY_FORMATS = Set.of("epub", "pdf", "mobi", "azw3");
    private static final long FAST_MS = 4_000;
    private static final long IDLE_MS = 30_000;
    private static final long OFFLINE_MS = 15_000;
    private static final long AUTH_RETRY_MS = 5 * 60_000;
    private static final long VIEWING_MS = 2 * 60_000;
    private static final int KEEP_PLACED = 30;

    public static class Plan {
        public String requestId;
        public String title;
        public String author;
        public String series;
        public String seriesIndex;
        public String by;
        public long at;
    }

    public static class Pending {
        public String hash;
        public String name;
        public long size;
        public String file;
        public String reason;
        public LibraryPlacer.Info suggestion;
        public String requestId;
        public String by;
        public long at;
    }

    public static class PlacedItem {
        public String hash;
        public String title;
        public String author;
        public String dest;
        public String requestId;
        public String bookId;
        public String by;
        public long at;
    }

    public static class Data {
        public Map<String, Plan> plans = new LinkedHashMap<>();
        public Map<String, Boolean> tracked = new LinkedHashMap<>();
        public Map<String, Pending> pending = new LinkedHashMap<>();
        public List<PlacedItem> placed = new ArrayList<>();
    }

    /** Una descarga en curso, con el libro al que va. */
    public record Item(AmuleClient.Download download, Plan plan, String target) {
    }

    public record Snapshot(boolean ok, String code, String error, AmuleClient.Status status, List<Item> downloads,
            List<Pending> pending, List<PlacedItem> placed, String library) {
    }

    private final AmuleClient client;
    private final Path incoming;
    private final CatalogIndexService catalog;
    private final BookRequests requests;
    private final AccessLog accessLog;
    private final LibraryPlacer placer;
    private final JsonStore<Data> store;
    private final Map<String, Long> searches = new ConcurrentHashMap<>();
    private final Set<String> busy = ConcurrentHashMap.newKeySet();

    private volatile Snapshot snapshot;
    private volatile long nextPollAt = 0;
    private volatile long viewedAt = 0;

    public AmuleService(
            @Value("${app.amule.url:http://127.0.0.1:4711}") String url,
            @Value("${app.amule.password:}") String password,
            @Value("${app.amule.incoming:/volume2/Multimedia/@amule/incoming}") String incoming,
            @Value("${app.data-dir:data}") String dataDir,
            CatalogIndexService catalog, BookRequests requests, AccessLog accessLog) {
        this.client = new AmuleClient(url, password);
        this.incoming = Path.of(incoming);
        this.catalog = catalog;
        this.requests = requests;
        this.accessLog = accessLog;
        this.placer = new LibraryPlacer(catalog.rootPath());
        this.store = new JsonStore<>(Path.of(dataDir, "amule.json"), Data.class, Data::new);
        this.snapshot = new Snapshot(false, client.configured() ? "offline" : "unconfigured",
                client.configured() ? null : "aMule no está configurado en BiblioLocal", null, List.of(),
                List.of(), List.of(), catalog.rootPath().toString());
    }

    public boolean enabled() {
        return client.configured();
    }

    private Data data() {
        return store.data();
    }

    // --- Sondeo -----------------------------------------------------------------

    /**
     * Cada 4 s se mira si toca preguntar a aMule: rapido mientras alguien mira el
     * panel o hay descargas de libros en marcha; despacio si no; muy de tarde en
     * tarde con la contraseña mal (amuleapi bloquea la IP tras muchos fallos).
     */
    @Scheduled(fixedDelay = FAST_MS)
    public void poll() {
        if (!enabled() || System.currentTimeMillis() < nextPollAt) return;
        long delay;
        try {
            refresh();
            boolean active = !data().plans.isEmpty() || !data().tracked.isEmpty()
                    || System.currentTimeMillis() - viewedAt < VIEWING_MS;
            delay = active ? FAST_MS : IDLE_MS;
        } catch (AmuleClient.AmuleException e) {
            delay = "auth".equals(e.code) ? AUTH_RETRY_MS : OFFLINE_MS;
        } catch (IOException e) {
            delay = OFFLINE_MS;
        }
        nextPollAt = System.currentTimeMillis() + delay - 500;
    }

    public synchronized Snapshot refresh() throws IOException {
        try {
            AmuleClient.Transfers t = client.transfers();
            List<AmuleClient.Download> books = t.downloads().stream()
                    .filter(d -> EBOOK.matcher(d.name()).matches())
                    .toList();
            handleCompleted(books);
            publish(true, null, null, t.status(), books);
        } catch (AmuleClient.AmuleException e) {
            publish(false, e.code, e.getMessage(), null, List.of());
            throw e;
        }
        return snapshot;
    }

    private void publish(boolean ok, String code, String error, AmuleClient.Status status,
            List<AmuleClient.Download> downloads) {
        List<Item> items = new ArrayList<>();
        for (AmuleClient.Download d : downloads) {
            Plan p = data().plans.get(d.hash());
            items.add(new Item(d, p, p == null ? null : safeTarget(p, d.name())));
        }
        snapshot = new Snapshot(ok, code, error, status, items, new ArrayList<>(data().pending.values()),
                new ArrayList<>(data().placed), catalog.rootPath().toString());
    }

    /** Lo que vera el panel. Marca que alguien esta mirando (se consulta mas a menudo). */
    public Snapshot snapshot() {
        viewedAt = System.currentTimeMillis();
        if (enabled() && System.currentTimeMillis() - nextPollAt > FAST_MS) {
            nextPollAt = 0;
        }
        return snapshot;
    }

    /** Progreso de las descargas que van a una peticion: id de la peticion → 0..1 */
    public Map<String, Double> progressByRequest() {
        Map<String, Double> result = new HashMap<>();
        for (Item item : snapshot.downloads()) {
            if (item.plan() != null && item.plan().requestId != null) {
                result.put(item.plan().requestId, item.download().progress());
            }
        }
        return result;
    }

    // --- Buscar y descargar ----------------------------------------------------------

    public long search(String user, String query, String network) throws IOException {
        long id = client.search(query, network, searches.get(key(user)));
        searches.put(key(user), id);
        return id;
    }

    public AmuleClient.Results results(String user) throws IOException {
        Long id = searches.get(key(user));
        if (id == null) return new AmuleClient.Results(List.of(), true, false);
        AmuleClient.Results r = client.results(id);
        // Primero los formatos de la biblioteca (epub el primero) y los que mas fuentes tienen
        List<AmuleClient.Result> sorted = new ArrayList<>(r.results());
        sorted.sort((a, b) -> {
            int fa = formatRank(a.name()), fb = formatRank(b.name());
            return fa != fb ? Integer.compare(fa, fb) : Integer.compare(b.sources(), a.sources());
        });
        return new AmuleClient.Results(sorted, r.done(), r.expired());
    }

    private static int formatRank(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".epub")) return 0;
        if (n.endsWith(".azw3") || n.endsWith(".mobi")) return 1;
        if (n.endsWith(".pdf")) return 2;
        return 3;
    }

    /**
     * Descargar un resultado (hash) o un enlace ed2k, apuntando para que libro es.
     * {@code plan.requestId}: la peticion que lo pidio, si la hay.
     */
    public synchronized void download(String user, String hash, String link, Plan plan, String ip) throws IOException {
        String h = hash;
        if (link != null && !link.isBlank()) {
            client.addLink(link);
            java.util.regex.Matcher m = Pattern.compile("\\|file\\|[^|]+\\|\\d+\\|([0-9a-fA-F]{32})\\|").matcher(link);
            h = m.find() ? m.group(1).toLowerCase(Locale.ROOT) : null;
        } else {
            client.download(hash == null ? "" : hash.toLowerCase(Locale.ROOT));
        }
        if (plan.requestId != null) {
            requests.find(plan.requestId).ifPresent(r -> {
                if (plan.title == null) plan.title = r.title;
                if (plan.author == null) plan.author = r.author;
                if (plan.series == null) {
                    plan.series = r.series;
                    plan.seriesIndex = r.seriesIndex;
                }
            });
        }
        plan.by = key(user);
        plan.at = System.currentTimeMillis();
        if (h != null) {
            data().plans.put(h.toLowerCase(Locale.ROOT), plan);
            store.save();
        }
        accessLog.event(AccessLog.Kind.AMULE, user, ip,
                "Descarga iniciada" + (plan.title != null ? ": «" + plan.title + "»" : ""), link != null ? "enlace ed2k" : h);
        nextPollAt = 0;
    }

    public void fileOp(String user, String hash, String op, String ip) throws IOException {
        client.fileOp(hash.toLowerCase(Locale.ROOT), op);
        if ("cancel".equals(op)) {
            synchronized (this) {
                Plan p = data().plans.remove(hash.toLowerCase(Locale.ROOT));
                data().tracked.remove(hash.toLowerCase(Locale.ROOT));
                store.save();
                accessLog.event(AccessLog.Kind.AMULE, user, ip,
                        "Descarga cancelada" + (p != null && p.title != null ? ": «" + p.title + "»" : ""));
            }
        }
        nextPollAt = 0;
    }

    // --- Al terminar ------------------------------------------------------------------

    private void handleCompleted(List<AmuleClient.Download> downloads) {
        boolean changed = false;
        for (AmuleClient.Download d : downloads) {
            if (!"complete".equals(d.state())) {
                if (data().tracked.putIfAbsent(d.hash(), true) == null) changed = true;
                continue;
            }
            // solo las que se han visto descargandose o se han lanzado desde aqui
            if (!data().tracked.containsKey(d.hash()) && !data().plans.containsKey(d.hash())) continue;
            if (data().pending.containsKey(d.hash()) || !busy.add(d.hash())) continue;
            try {
                finish(d);
            } finally {
                busy.remove(d.hash());
            }
            changed = true;
        }
        Set<String> live = new java.util.HashSet<>(downloads.stream().map(AmuleClient.Download::hash).toList());
        changed |= data().tracked.keySet().removeIf(h -> !live.contains(h));
        // Un enlace recien añadido tarda un poco en aparecer en la lista de aMule
        long limit = System.currentTimeMillis() - 10 * 60_000;
        changed |= data().plans.entrySet().removeIf(e -> !live.contains(e.getKey()) && e.getValue().at < limit);
        if (changed) store.save();
    }

    private void finish(AmuleClient.Download d) {
        Plan plan = data().plans.get(d.hash());
        Path file = incoming.resolve(d.name()).normalize();
        if (!file.getParent().equals(incoming.normalize()) || !Path.of(d.name()).getFileName().toString().equals(d.name())) {
            addPending(d, file, "Nombre de archivo no válido", null, plan);
            return;
        }
        if (!Files.isRegularFile(file)) {
            addPending(d, file, "No está en la carpeta de descargas de aMule", null, plan);
            return;
        }
        String ext = extension(d.name());
        LibraryPlacer.Info info = placer.identify(file, plan == null ? null
                : new LibraryPlacer.Info(plan.title, plan.author, plan.series, plan.seriesIndex));
        if (!LIBRARY_FORMATS.contains(ext)) {
            addPending(d, file, "La biblioteca no lee archivos ." + ext, info, plan);
            return;
        }
        if (!info.complete()) {
            addPending(d, file, "No se sabe de qué libro es: el archivo no trae título y autor", info, plan);
            return;
        }
        try {
            place(d.hash(), d.name(), file, info, plan == null ? null : plan.requestId, plan == null ? null : plan.by);
        } catch (IOException | RuntimeException e) {
            log.error("No se pudo colocar {}", d.name(), e);
            addPending(d, file, "No se pudo mover a la biblioteca: " + e.getMessage(), info, plan);
        }
    }

    private void place(String hash, String name, Path file, LibraryPlacer.Info info, String requestId, String by)
            throws IOException {
        String relative = placer.targetFor(info, extension(name), catalog.findAll(null, null, null));
        LibraryPlacer.Placed placed = placer.place(file, info, relative);
        catalog.refresh();
        Book book = catalog.findAll(null, null, null).stream()
                .filter(b -> b.relativePath().replace('\\', '/').equals(placed.relative()))
                .findFirst().orElse(null);
        if (requestId != null && book != null) requests.fulfilled(requestId, book, by);

        PlacedItem p = new PlacedItem();
        p.hash = hash;
        p.title = info.title();
        p.author = info.author();
        p.dest = placed.relative();
        p.requestId = requestId;
        p.bookId = book == null ? null : book.id();
        p.by = by;
        p.at = System.currentTimeMillis();
        data().placed.add(0, p);
        while (data().placed.size() > KEEP_PLACED) data().placed.remove(data().placed.size() - 1);
        data().plans.remove(hash);
        data().pending.remove(hash);
        data().tracked.remove(hash);
        store.save();
        try {
            client.fileOp(hash, "clear");
        } catch (IOException ignored) {
            // si no se puede quitar de la lista de aMule, se queda como terminada
        }
        accessLog.event(AccessLog.Kind.AMULE, by, null, "Colocado: «" + info.title() + "»", placed.relative());
        log.info("Descarga colocada en la biblioteca: {}", placed.relative());
    }

    private void addPending(AmuleClient.Download d, Path file, String reason, LibraryPlacer.Info suggestion, Plan plan) {
        Pending p = new Pending();
        p.hash = d.hash();
        p.name = d.name();
        p.size = d.size();
        p.file = file.toString();
        p.reason = reason;
        p.suggestion = suggestion;
        p.requestId = plan == null ? null : plan.requestId;
        p.by = plan == null ? null : plan.by;
        p.at = System.currentTimeMillis();
        data().pending.put(d.hash(), p);
        data().plans.remove(d.hash());
        data().tracked.remove(d.hash());
        store.save();
        try {
            client.fileOp(d.hash(), "clear");
        } catch (IOException ignored) {
            // queda en la lista de aMule como terminada; aqui ya esta apuntada
        }
        accessLog.event(AccessLog.Kind.AMULE, p.by, null, "Pendiente de colocar: " + d.name(), reason);
    }

    /** Colocar a mano una pendiente, con los datos que pone el administrador. */
    public synchronized PlacedItem placePending(String user, String hash, LibraryPlacer.Info info, String ip)
            throws IOException {
        Pending p = data().pending.get(hash.toLowerCase(Locale.ROOT));
        if (p == null) throw new IllegalArgumentException("Esa descarga ya no está pendiente");
        Path file = Path.of(p.file);
        if (!Files.isRegularFile(file)) throw new IllegalArgumentException("El archivo ya no está en la carpeta de aMule");
        if (!info.complete()) throw new IllegalArgumentException("Indica al menos el título y el autor");
        if (!LIBRARY_FORMATS.contains(extension(p.name))) {
            throw new IllegalArgumentException("La biblioteca no lee archivos ." + extension(p.name));
        }
        place(p.hash, p.name, file, info, p.requestId, key(user));
        accessLog.event(AccessLog.Kind.AMULE, user, ip, "Colocado a mano: «" + info.title() + "»");
        return data().placed.get(0);
    }

    public synchronized void dismissPending(String user, String hash, String ip) {
        Pending p = data().pending.remove(hash.toLowerCase(Locale.ROOT));
        if (p == null) throw new IllegalArgumentException("Esa descarga ya no está pendiente");
        store.save();
        accessLog.event(AccessLog.Kind.AMULE, user, ip, "Descartada de pendientes: " + p.name,
                "el archivo sigue en la carpeta de aMule");
    }

    public synchronized void clearPlaced() {
        data().placed.clear();
        store.save();
    }

    /** Donde iria, para enseñarlo en el panel mientras se descarga. */
    private String safeTarget(Plan p, String name) {
        if (p.title == null || p.author == null) return null;
        try {
            return placer.targetFor(new LibraryPlacer.Info(p.title, p.author, p.series, p.seriesIndex),
                    extension(name), catalog.findAll(null, null, null));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String key(String user) {
        return user == null ? "" : user.toLowerCase(Locale.ROOT);
    }
}
