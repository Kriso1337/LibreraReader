package com.foobnix.catalog;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.SSLException;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** Blocking API: callers run load/search off the UI thread. No Activity is retained. */
public final class CatalogRepository {
    public static final String[] MIRRORS = {
            "https://flibusta.is/", "https://proxy.flibusta.is/", "https://flibusta.site/"
    };
    private static final Object CACHE_LOCK = new Object();
    private static final int CACHE_VERSION = 1;
    private static final long CACHE_LIMIT = 16L * 1024 * 1024;
    private static final int CACHE_FILES = 48;
    private final SharedPreferences preferences;
    private final File cache;
    private final CatalogParser parser = new CatalogParser();
    private final OkHttpClient client;
    private final AtomicInteger cancellation = new AtomicInteger();
    private volatile String successfulMirror = MIRRORS[0];

    public CatalogRepository(Context context) {
        this(new File(context.getApplicationContext().getFilesDir(), "catalog-pages-v1"),
                context.getApplicationContext().getSharedPreferences("flibusta_catalog", Context.MODE_PRIVATE),
                new OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
                .callTimeout(25, TimeUnit.SECONDS)
                .followRedirects(false).followSslRedirects(false)
                .build());
    }

    CatalogRepository(File cacheDirectory, SharedPreferences settings, OkHttpClient httpClient) {
        cache = cacheDirectory;
        preferences = settings;
        client = httpClient;
    }

    /** Empty string means automatic; an explicitly selected mirror never silently changes. */
    public String getMirror() { return preferences.getString("mirror", ""); }

    public void setMirror(String mirror) {
        String chosen = "";
        if (mirror != null && !mirror.trim().isEmpty()) {
            try {
                HttpUrl parsed = HttpUrl.parse(CatalogUrls.validate(mirror));
                if (!parsed.encodedPath().equals("/") || parsed.query() != null) {
                    throw new IOException("Expected mirror origin");
                }
                chosen = parsed.toString();
            } catch (IOException error) {
                throw new IllegalArgumentException("Unsupported mirror", error);
            }
        }
        preferences.edit().putString("mirror", chosen).apply();
    }

    public CatalogPage load(String url, boolean offline) throws IOException {
        int generation = cancellation.get();
        String selected = getMirror();
        String first = selected.isEmpty() ? successfulMirror : selected;
        String requested = url == null || url.isEmpty() ? first + "opds" : CatalogUrls.validate(url);
        if (!CatalogUrls.isCatalogUrl(requested) || requested.contains("/opds-opensearch.xml")) {
            throw new IOException("Not an OPDS page");
        }
        requested = CatalogUrls.onMirror(requested, first);
        if (offline) return cachedPage(requested);

        List<String> attempts = new ArrayList<>();
        attempts.add(first);
        if (selected.isEmpty()) for (String mirror : MIRRORS) if (!attempts.contains(mirror)) attempts.add(mirror);
        IOException failure = null;
        for (String mirror : attempts) {
            if (Thread.currentThread().isInterrupted() || generation != cancellation.get()) throw new IOException("Catalog request cancelled");
            String attempt = CatalogUrls.onMirror(requested, mirror);
            try {
                Document document = fetch(attempt);
                CatalogPage page = parser.parse(document.bytes, document.url);
                page.fetchedAt = System.currentTimeMillis();
                successfulMirror = HttpUrl.parse(document.url).newBuilder().encodedPath("/").query(null).build().toString();
                save(attempt, document, page.fetchedAt);
                return page;
            } catch (SSLException error) {
                // Certificate failures are not treated as ordinary unavailable mirrors.
                throw error;
            } catch (HttpStatusException error) {
                if (!error.retryable) throw error;
                failure = error;
            } catch (IOException error) {
                if (generation != cancellation.get()) throw new IOException("Catalog request cancelled", error);
                failure = error;
            }
        }
        // Network failure can leave the last successful page useful. Its age and
        // cached status are explicit so it cannot masquerade as a fresh response.
        try { return cachedPage(requested); }
        catch (IOException cacheMiss) { throw failure == null ? cacheMiss : failure; }
    }

    public CatalogPage search(String query, boolean offline) throws IOException {
        if (query == null || query.trim().isEmpty()) return load(null, offline);
        CatalogPage root = load(null, offline);
        String template = root.searchTemplate;
        if (template.isEmpty() && !root.searchDescriptionUrl.isEmpty()) {
            Document description;
            if (offline || root.fromCache) description = read(root.searchDescriptionUrl);
            else {
                description = fetch(root.searchDescriptionUrl);
                // Validate before caching; the HTML error page is never stored.
                parser.parseSearchDescription(description.bytes, description.url);
                save(root.searchDescriptionUrl, description, System.currentTimeMillis());
            }
            template = parser.parseSearchDescription(description.bytes, description.url);
        }
        if (template.isEmpty()) throw new IOException("Catalog does not advertise search");
        return load(CatalogUrls.searchUrl(template, query), offline);
    }

    /** Cancels outstanding calls when the owning screen is disposed. */
    public void cancel() { cancellation.incrementAndGet(); client.dispatcher().cancelAll(); }

    private Document fetch(String address) throws IOException {
        int generation = cancellation.get();
        String current = CatalogUrls.validate(address);
        for (int redirect = 0; redirect <= 4; redirect++) {
            if (generation != cancellation.get()) throw new IOException("Catalog request cancelled");
            if (!CatalogUrls.isCatalogUrl(current)) throw new IOException("Unexpected catalog redirect");
            Request request = new Request.Builder().url(current)
                    .header("User-Agent", "Librera-Catalog/1")
                    .header("Accept", "application/atom+xml, application/opensearchdescription+xml, application/xml;q=0.8")
                    .build();
            try (Response response = client.newCall(request).execute()) {
                if (response.isRedirect()) {
                    if (redirect == 4) throw new IOException("Too many catalog redirects");
                    String location = response.header("Location");
                    // Redirects are stricter than legacy feed links: no HTTP downgrade.
                    HttpUrl next = response.request().url().resolve(location == null ? "" : location);
                    if (location == null || next == null) throw new IOException("Invalid catalog redirect");
                    current = CatalogUrls.validate(next.toString());
                    continue;
                }
                if (!response.isSuccessful()) {
                    int code = response.code();
                    throw new HttpStatusException(code, code >= 500 && code <= 599);
                }
                ResponseBody body = response.body();
                if (body == null) throw new IOException("Missing catalog body");
                String contentType = response.header("Content-Type", "").toLowerCase(Locale.ROOT);
                if (!contentType.contains("xml")) throw new IOException("Expected XML catalog response");
                if (body.contentLength() > CatalogParser.MAX_BYTES) throw new IOException("Oversized catalog response");
                return new Document(current, bounded(body.byteStream()), System.currentTimeMillis());
            }
        }
        throw new IOException("Catalog request failed");
    }

    private static byte[] bounded(InputStream stream) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = stream.read(buffer)) != -1) {
            if (output.size() + count > CatalogParser.MAX_BYTES) throw new IOException("Oversized catalog response");
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private CatalogPage cachedPage(String address) throws IOException {
        Document cached = read(address);
        CatalogPage page = parser.parse(cached.bytes, cached.url);
        page.fromCache = true;
        page.fetchedAt = cached.fetchedAt;
        return page;
    }

    private Document read(String address) throws IOException {
        synchronized (CACHE_LOCK) {
            File file = cacheFile(address);
            if (!file.isFile()) throw new IOException("No saved catalog page");
            try (DataInputStream input = new DataInputStream(new FileInputStream(file))) {
                if (input.readInt() != CACHE_VERSION) throw new IOException("Unknown catalog cache version");
                long fetched = input.readLong();
                String key = input.readUTF();
                String url = CatalogUrls.validate(input.readUTF());
                if (!CatalogUrls.cacheKey(address).equals(key)) throw new IOException("Catalog cache mismatch");
                int size = input.readInt();
                if (size <= 0 || size > CatalogParser.MAX_BYTES) throw new IOException("Invalid catalog cache size");
                byte[] data = new byte[size];
                input.readFully(data);
                return new Document(url, data, fetched);
            }
        }
    }

    private void save(String address, Document document, long fetchedAt) {
        synchronized (CACHE_LOCK) {
            File temporary = null;
            try {
                if (!cache.isDirectory() && !cache.mkdirs()) return;
                temporary = File.createTempFile("catalog-", ".tmp", cache);
                try (FileOutputStream file = new FileOutputStream(temporary);
                     DataOutputStream output = new DataOutputStream(file)) {
                    output.writeInt(CACHE_VERSION); output.writeLong(fetchedAt);
                    output.writeUTF(CatalogUrls.cacheKey(address));
                    output.writeUTF(document.url); output.writeInt(document.bytes.length);
                    output.write(document.bytes); output.flush(); file.getFD().sync();
                }
                File destination = cacheFile(address);
                if (!temporary.renameTo(destination)) return;
                prune();
            } catch (IOException ignored) {
                // A full disk must not turn a successful online lookup into failure.
            } finally {
                if (temporary != null && temporary.exists()) temporary.delete();
            }
        }
    }

    private void prune() {
        File[] files = cache.listFiles((directory, name) -> name.endsWith(".feed"));
        if (files == null) return;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
        long total = 0;
        for (int index = 0; index < files.length; index++) {
            total += files[index].length();
            if (index >= CACHE_FILES || total > CACHE_LIMIT) files[index].delete();
        }
    }

    private File cacheFile(String address) throws IOException {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    CatalogUrls.cacheKey(address).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte value : digest) hex.append(String.format(Locale.ROOT, "%02x", value & 255));
            return new File(cache, hex + ".feed");
        } catch (NoSuchAlgorithmException impossible) { throw new IOException(impossible); }
    }

    private static final class Document {
        final String url;
        final byte[] bytes;
        final long fetchedAt;
        Document(String url, byte[] bytes, long fetchedAt) {
            this.url = url; this.bytes = bytes; this.fetchedAt = fetchedAt;
        }
    }

    public static final class HttpStatusException extends IOException {
        public final int statusCode;
        final boolean retryable;
        HttpStatusException(int code, boolean retryable) {
            super("Catalog HTTP " + code); statusCode = code; this.retryable = retryable;
        }
    }
}
