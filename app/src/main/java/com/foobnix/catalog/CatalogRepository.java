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
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

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
    private static final int MEMORY_PAGES = 8;
    private static final long MEMORY_LIMIT = 4L * 1024 * 1024;
    private static final LinkedHashMap<String, ParsedPage> PARSED = new LinkedHashMap<>(16, .75f, true);
    private static long parsedBytes;
    public static final long FRESH_FOR_MS = TimeUnit.MINUTES.toMillis(15);
    private static final int LANDING_HOPS = 3;
    private final SharedPreferences preferences;
    private final File cache;
    private final CatalogParser parser = new CatalogParser();
    private final OkHttpClient client;
    private final AtomicInteger cancellation = new AtomicInteger();
    private volatile String successfulMirror = MIRRORS[0];
    private BooleanSupplier networkAllowed = () -> true;

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
        File canonical;
        try { canonical = cacheDirectory.getCanonicalFile(); }
        catch (IOException unavailable) { canonical = cacheDirectory.getAbsoluteFile(); }
        cache = canonical;
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
        checkNetworkAllowed();

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
                save(attempt, document, page.fetchedAt, page);
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
        CatalogPage root = loadFresh(null, offline);
        return load(CatalogUrls.searchUrl(searchTemplate(root, offline), query), offline);
    }

    /** Disk-only lookup for an immediate first frame; the caller decides when to refresh. */
    public CatalogPage cached(String url) throws IOException { return load(url, true); }

    public CatalogPage cachedSearch(String query) throws IOException {
        if (query == null || query.trim().isEmpty()) return cached(null);
        return cached(CatalogUrls.searchUrl(searchTemplate(cached(null), true), query));
    }

    public static boolean isFresh(CatalogPage page) {
        return page != null && isFresh(page.fetchedAt);
    }

    private static boolean isFresh(long fetchedAt) {
        long age = System.currentTimeMillis() - fetchedAt;
        return fetchedAt > 0 && age >= 0 && age < FRESH_FOR_MS;
    }

    /** Prefer a recent saved page without adding a network round trip. */
    public CatalogPage loadFresh(String url, boolean offline) throws IOException {
        try {
            CatalogPage saved = cached(url);
            if (offline || isFresh(saved)) return saved;
        } catch (IOException miss) { if (offline) throw miss; }
        return load(url, false);
    }

    /** The remembered home is a feed of books, not the root's list of directories. */
    public CatalogPage cachedLanding() throws IOException {
        String address = preferences.getString("landing_url", "");
        if (!address.isEmpty()) {
            try {
                CatalogPage saved = cached(address);
                if (hasBooks(saved)) return saved;
            } catch (IOException miss) { /* A bounded cache can evict an old home page. */ }
        }
        return discoverLanding(true, true);
    }

    /** Follow at most three advertised navigation links. No book or cover is downloaded. */
    public CatalogPage loadLanding(boolean offline) throws IOException {
        if (offline) return cachedLanding();
        return discoverLanding(false, false);
    }

    private CatalogPage discoverLanding(boolean offline, boolean preferFresh) throws IOException {
        // Re-discover the new-books path when refreshing: some catalogs publish a
        // new dated URL rather than updating the URL remembered on the previous day.
        CatalogPage page = preferFresh ? loadFresh(null, offline) : load(null, offline);
        Set<String> visited = new HashSet<>();
        visited.add(CatalogUrls.cacheKey(page.url));
        for (int hops = 0; !hasBooks(page) && hops < LANDING_HOPS; hops++) {
            String next = landingNavigation(page);
            if (next.isEmpty() || !visited.add(CatalogUrls.cacheKey(next))) break;
            page = preferFresh ? loadFresh(next, offline) : load(next, offline);
        }
        if (hasBooks(page)) preferences.edit().putString("landing_url", page.url).apply();
        return page;
    }

    static boolean hasBooks(CatalogPage page) {
        for (CatalogEntry entry : page.entries) if (!entry.acquisitions.isEmpty()) return true;
        return false;
    }

    private static String landingNavigation(CatalogPage page) {
        String fallback = "";
        for (CatalogEntry entry : page.entries) {
            if (entry.navigationUrl.isEmpty() || !entry.acquisitions.isEmpty()) continue;
            if (fallback.isEmpty()) fallback = entry.navigationUrl;
            String title = entry.title.toLowerCase(Locale.ROOT);
            String path = HttpUrl.parse(entry.navigationUrl).encodedPath().toLowerCase(Locale.ROOT);
            if (path.contains("/new") || title.startsWith("нов") || title.startsWith("new")
                    || title.startsWith("recent")) return entry.navigationUrl;
        }
        return fallback;
    }

    /** Bounded speculative work: root, <=3 navigation feeds, and one next page. */
    public CatalogPage warmLanding() throws IOException {
        CatalogPage saved;
        try { saved = cachedLanding(); }
        catch (IOException miss) { saved = null; }
        CatalogPage home = isFresh(saved) && hasBooks(saved) ? saved : discoverLanding(false, true);
        if (hasBooks(home) && !home.nextUrl.isEmpty()
                && !CatalogUrls.cacheKey(home.url).equals(CatalogUrls.cacheKey(home.nextUrl))) {
            loadFresh(home.nextUrl, false);
        }
        return home;
    }

    private String searchTemplate(CatalogPage root, boolean offline) throws IOException {
        String template = root.searchTemplate;
        if (template.isEmpty() && !root.searchDescriptionUrl.isEmpty()) {
            Document description = null;
            try { description = read(root.searchDescriptionUrl); }
            catch (IOException miss) { if (offline) throw miss; }
            if (description == null || (!offline && !isFresh(description.fetchedAt))) {
                String selected = getMirror();
                description = fetch(CatalogUrls.onMirror(root.searchDescriptionUrl,
                        selected.isEmpty() ? successfulMirror : selected));
                // Validate before caching; the HTML error page is never stored.
                parser.parseSearchDescription(description.bytes, description.url);
                save(root.searchDescriptionUrl, description, System.currentTimeMillis());
            }
            template = parser.parseSearchDescription(description.bytes, description.url);
        }
        if (template.isEmpty()) throw new IOException("Catalog does not advertise search");
        return template;
    }

    /** Worker guard is checked before every HTTP request, including redirects/retries. */
    void setNetworkAllowed(BooleanSupplier allowed) { networkAllowed = allowed; }

    private void checkNetworkAllowed() throws IOException {
        if (Thread.currentThread().isInterrupted() || !networkAllowed.getAsBoolean()) {
            throw new IOException("Catalog request cancelled");
        }
    }

    /** Cancels outstanding calls when the owning screen is disposed. */
    public void cancel() { cancellation.incrementAndGet(); client.dispatcher().cancelAll(); }

    private Document fetch(String address) throws IOException {
        int generation = cancellation.get();
        String current = CatalogUrls.validate(address);
        for (int redirect = 0; redirect <= 4; redirect++) {
            checkNetworkAllowed();
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
        synchronized (CACHE_LOCK) {
            File file = cacheFile(address);
            ParsedPage memory = PARSED.get(file.getAbsolutePath());
            if (memory != null && file.isFile() && memory.modified == file.lastModified()
                    && memory.length == file.length()) {
                CatalogPage page = copyPage(memory.page);
                page.fromCache = true;
                return page;
            }
            forgetParsed(file);
            Document cached = read(address);
            CatalogPage page = parser.parse(cached.bytes, cached.url);
            page.fromCache = true;
            page.fetchedAt = cached.fetchedAt;
            rememberParsed(file, page);
            return page;
        }
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
        save(address, document, fetchedAt, null);
    }

    private void save(String address, Document document, long fetchedAt, CatalogPage parsed) {
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
                // Disk replacement and parsed publication share a lock across all
                // repositories. A slower warmup cannot publish stale objects after
                // another repository has already replaced this file.
                forgetParsed(destination);
                if (parsed != null) rememberParsed(destination, parsed);
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
            if ((index >= CACHE_FILES || total > CACHE_LIMIT) && files[index].delete()) forgetParsed(files[index]);
        }
    }

    private File cacheFile(String address) throws IOException {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    CatalogUrls.cacheKey(address).getBytes(StandardCharsets.UTF_8));
            char[] hex = new char[digest.length * 2];
            char[] alphabet = "0123456789abcdef".toCharArray();
            for (int i = 0; i < digest.length; i++) {
                hex[i * 2] = alphabet[(digest[i] & 255) >>> 4];
                hex[i * 2 + 1] = alphabet[digest[i] & 15];
            }
            return new File(cache, new String(hex) + ".feed");
        } catch (NoSuchAlgorithmException impossible) { throw new IOException(impossible); }
    }

    private static void forgetParsed(File file) {
        ParsedPage removed = PARSED.remove(file.getAbsolutePath());
        if (removed != null) parsedBytes -= removed.bytes;
    }

    /** Called only under CACHE_LOCK. Strings are immutable; all mutable models are copied. */
    private static void rememberParsed(File file, CatalogPage page) {
        forgetParsed(file);
        long bytes = estimatedBytes(page);
        if (bytes > MEMORY_LIMIT || !file.isFile()) return;
        PARSED.put(file.getAbsolutePath(), new ParsedPage(copyPage(page), file.lastModified(), file.length(), bytes));
        parsedBytes += bytes;
        Iterator<Map.Entry<String, ParsedPage>> oldest = PARSED.entrySet().iterator();
        while ((PARSED.size() > MEMORY_PAGES || parsedBytes > MEMORY_LIMIT) && oldest.hasNext()) {
            parsedBytes -= oldest.next().getValue().bytes;
            oldest.remove();
        }
    }

    private static long estimatedBytes(CatalogPage page) {
        long bytes = 192 + textBytes(page.title) + textBytes(page.url) + textBytes(page.nextUrl)
                + textBytes(page.searchTemplate) + textBytes(page.searchDescriptionUrl);
        for (CatalogEntry entry : page.entries) {
            bytes += 128 + textBytes(entry.id) + textBytes(entry.title) + textBytes(entry.author)
                    + textBytes(entry.summary) + textBytes(entry.coverUrl) + textBytes(entry.navigationUrl);
            for (CatalogAcquisition acquisition : entry.acquisitions) {
                bytes += 64 + textBytes(acquisition.url) + textBytes(acquisition.mimeType) + textBytes(acquisition.format);
            }
        }
        return bytes;
    }

    private static long textBytes(String value) { return value == null ? 0 : 40L + value.length() * 2L; }

    private static CatalogPage copyPage(CatalogPage original) {
        CatalogPage result = new CatalogPage();
        result.title = original.title; result.url = original.url; result.nextUrl = original.nextUrl;
        result.searchTemplate = original.searchTemplate; result.searchDescriptionUrl = original.searchDescriptionUrl;
        result.fetchedAt = original.fetchedAt; result.fromCache = original.fromCache;
        for (CatalogEntry source : original.entries) {
            CatalogEntry entry = new CatalogEntry();
            entry.id = source.id; entry.title = source.title; entry.author = source.author;
            entry.summary = source.summary; entry.coverUrl = source.coverUrl; entry.navigationUrl = source.navigationUrl;
            for (CatalogAcquisition sourceFormat : source.acquisitions) {
                CatalogAcquisition format = new CatalogAcquisition();
                format.url = sourceFormat.url; format.mimeType = sourceFormat.mimeType; format.format = sourceFormat.format;
                entry.acquisitions.add(format);
            }
            result.entries.add(entry);
        }
        return result;
    }

    private static final class ParsedPage {
        final CatalogPage page;
        final long modified, length, bytes;
        ParsedPage(CatalogPage page, long modified, long length, long bytes) {
            this.page = page; this.modified = modified; this.length = length; this.bytes = bytes;
        }
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
