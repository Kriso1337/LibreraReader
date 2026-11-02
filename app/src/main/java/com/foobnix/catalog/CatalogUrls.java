package com.foobnix.catalog;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import okhttp3.HttpUrl;

/** URL boundary shared by catalog requests and the download engine. */
public final class CatalogUrls {
    public static final int MAX_URL_LENGTH = 4096;
    private static final Set<String> HOSTS = new HashSet<>(Arrays.asList(
            "flibusta.is", "proxy.flibusta.is", "flibusta.site", "x.flibusta.is"));
    private static final Set<String> DOWNLOAD_HOSTS = new HashSet<>(Arrays.asList(
            "flibusta.is", "proxy.flibusta.is", "flibusta.site", "x.flibusta.is", "static.flibusta.is"));
    private static final String PLACEHOLDER = "LIBRERA_SEARCH_TERMS_PLACEHOLDER";

    private CatalogUrls() { }

    /** Requests never accept credentials, a nonstandard port, HTTP, or an unknown origin. */
    public static String validate(String value) throws IOException {
        return validate(value, HOSTS);
    }

    /** The file service uses this exact CDN; it is deliberately not a catalog origin. */
    public static String validateDownload(String value) throws IOException {
        return validate(value, DOWNLOAD_HOSTS);
    }

    private static String validate(String value, Set<String> hosts) throws IOException {
        if (value == null || value.length() > MAX_URL_LENGTH) throw new IOException("Oversized catalog address");
        HttpUrl parsed = HttpUrl.parse(value);
        if (parsed == null || !"https".equals(parsed.scheme()) || parsed.port() != 443
                || !hosts.contains(parsed.host().toLowerCase(Locale.ROOT))
                || !parsed.username().isEmpty() || !parsed.password().isEmpty()) {
            throw new IOException("Unsupported catalog address");
        }
        String canonical = parsed.newBuilder().fragment(null).build().toString();
        if (canonical.length() > MAX_URL_LENGTH) throw new IOException("Oversized catalog address");
        return canonical;
    }

    public static String resolve(String base, String reference) throws IOException {
        return resolve(base, reference, HOSTS);
    }

    public static String resolveDownload(String base, String reference) throws IOException {
        return resolve(base, reference, DOWNLOAD_HOSTS);
    }

    private static String resolve(String base, String reference, Set<String> hosts) throws IOException {
        HttpUrl origin = HttpUrl.parse(validate(base, hosts));
        if (reference == null || reference.trim().isEmpty() || reference.length() > MAX_URL_LENGTH) {
            throw new IOException("Missing catalog link");
        }
        HttpUrl result = origin.resolve(reference.trim());
        if (result == null) throw new IOException("Invalid catalog link");
        // Historical feeds sometimes advertise http://flibusta.is links. Upgrade only
        // these already-known source hosts, before any request is made.
        if ("http".equals(result.scheme()) && result.port() == 80
                && hosts.contains(result.host())) {
            result = result.newBuilder().scheme("https").port(443).build();
        }
        return validate(result.toString(), hosts);
    }

    public static String resolveTemplate(String base, String template) throws IOException {
        if (template == null || !template.contains("{searchTerms}")) {
            throw new IOException("Unsupported search template");
        }
        String expanded = template.replace("{searchTerms}", PLACEHOLDER)
                .replace("{startPage?}", "1").replace("{startIndex?}", "0")
                .replace("{count?}", "20").replace("{language?}", "ru")
                .replace("{inputEncoding?}", "UTF-8").replace("{outputEncoding?}", "UTF-8");
        if (expanded.contains("{") || expanded.contains("}")) {
            throw new IOException("Unsupported search parameter");
        }
        return resolve(base, expanded).replace(PLACEHOLDER, "{searchTerms}");
    }

    public static String searchUrl(String template, String query) throws IOException {
        if (query == null || query.trim().isEmpty() || query.length() > 300) {
            throw new IOException("Empty or oversized search query");
        }
        return validate(template.replace("{searchTerms}",
                URLEncoder.encode(query.trim(), "UTF-8")));
    }

    public static boolean isCatalogUrl(String value) {
        try {
            String path = HttpUrl.parse(validate(value)).encodedPath();
            return path.equals("/opds") || path.startsWith("/opds/")
                    || path.equals("/opds-opensearch.xml");
        } catch (IOException ignored) {
            return false;
        }
    }

    public static String onMirror(String value, String mirror) throws IOException {
        HttpUrl input = HttpUrl.parse(validate(value));
        HttpUrl target = HttpUrl.parse(validate(mirror));
        if (!isCatalogUrl(value)) throw new IOException("Not a catalog navigation URL");
        return input.newBuilder().scheme("https").host(target.host()).port(443).build().toString();
    }

    /** A page remains the same page when a configured mirror changes. */
    public static String cacheKey(String value) throws IOException {
        HttpUrl input = HttpUrl.parse(validate(value));
        return input.encodedPath() + (input.encodedQuery() == null ? "" : "?" + input.encodedQuery());
    }
}
