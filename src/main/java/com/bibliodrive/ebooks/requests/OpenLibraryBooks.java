package com.bibliodrive.ebooks.requests;

import com.bibliodrive.ebooks.catalog.Names;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Buscar libros para pedirlos, en Open Library (sin clave). Con {@code lang=es}
 * Open Library elige para cada obra la edicion en espanol si la hay, asi que «El
 * camino de los reyes» encuentra «The Way of Kings» con su titulo en espanol.
 */
@Service
public class OpenLibraryBooks {

    private static final String SEARCH = "https://openlibrary.org/search.json?lang=es&limit=12"
            + "&fields=key,title,author_name,first_publish_year,cover_i,edition_count,"
            + "editions,editions.title,editions.language,editions.cover_i,editions.publisher,editions.publish_date&q=";
    private static final String USER_AGENT = "BiblioLocal/1.0 (biblioteca personal)";

    /**
     * Una obra encontrada. {@code title}: el de la edicion en espanol si la hay;
     * {@code originalTitle}: el de la obra, si es distinto.
     */
    public record Found(String olKey, String title, String originalTitle, String author, Integer year,
            Long coverId, String publisher, boolean spanish, int editions) {
    }

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public List<Found> search(String query) throws IOException, InterruptedException {
        String q = query == null ? "" : query.trim();
        if (q.length() < 2) return List.of();
        HttpRequest request = HttpRequest.newBuilder(URI.create(SEARCH + URLEncoder.encode(q, StandardCharsets.UTF_8)))
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();
        HttpResponse<InputStream> res = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        JsonNode root;
        try (InputStream body = res.body()) {
            if (res.statusCode() != 200) throw new IOException("Open Library respondió " + res.statusCode());
            root = json.readTree(body);
        }

        List<Found> found = new ArrayList<>();
        for (JsonNode doc : root.path("docs")) {
            String key = doc.path("key").asText("");
            String workTitle = doc.path("title").asText(null);
            String author = doc.path("author_name").path(0).asText(null);
            if (!key.startsWith("/works/") || workTitle == null || author == null) continue;
            JsonNode edition = doc.path("editions").path("docs").path(0);
            boolean spanish = false;
            for (JsonNode lang : edition.path("language")) {
                if ("spa".equals(lang.asText())) spanish = true;
            }
            String title = spanish ? Names.tidyCase(edition.path("title").asText(workTitle)) : workTitle;
            long cover = spanish && edition.path("cover_i").asLong(0) > 0
                    ? edition.path("cover_i").asLong() : doc.path("cover_i").asLong(0);
            int year = doc.path("first_publish_year").asInt(0);
            found.add(new Found(key, title,
                    Names.titleKey(title).equals(Names.titleKey(workTitle)) ? null : workTitle,
                    author, year > 0 ? year : null, cover > 0 ? cover : null,
                    spanish ? edition.path("publisher").path(0).asText(null) : null,
                    spanish, doc.path("edition_count").asInt(0)));
        }
        // Primero las que tienen edicion en espanol, sin perder el orden de relevancia
        List<Found> sorted = new ArrayList<>(found.stream().filter(Found::spanish).toList());
        sorted.addAll(found.stream().filter(f -> !f.spanish()).toList());
        return sorted;
    }
}
