package com.foobnix.catalog;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.Authenticator;
import okhttp3.Call;
import okhttp3.CookieJar;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** One cancellable cover fetch; no WebView cookies, OPDS credentials, or automatic redirects. */
final class CatalogCoverFetcher {
    static final int MAX_BYTES = 4 * 1024 * 1024;
    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false).build();

    private final OkHttpClient client;
    private volatile Call call;
    private volatile boolean cancelled;

    CatalogCoverFetcher() { this(HTTP); }

    CatalogCoverFetcher(OkHttpClient client) {
        this.client = client.newBuilder().followRedirects(false).followSslRedirects(false)
                .authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
                .cookieJar(CookieJar.NO_COOKIES).build();
    }

    byte[] fetch(String address) throws IOException {
        HttpUrl url = HttpUrl.parse(CatalogUrls.validate(address));
        for (int redirects = 0; redirects <= 4; redirects++) {
            checkCancelled();
            Request request = new Request.Builder().url(url)
                    .header("User-Agent", "Librera-Catalog/1.0").header("Accept", "image/*")
                    .build();
            call = client.newCall(request);
            checkCancelled();
            try (Response response = call.execute()) {
                int status = response.code();
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    String location = response.header("Location");
                    HttpUrl resolved = location == null ? null : url.resolve(location);
                    // Unlike old feed metadata, a live redirect is never upgraded from HTTP.
                    url = HttpUrl.parse(CatalogUrls.validate(resolved == null ? null : resolved.toString()));
                    continue;
                }
                if (status != 200) throw new IOException("Cover HTTP " + status);
                ResponseBody body = response.body();
                if (body == null || body.contentLength() > MAX_BYTES) throw new IOException("Invalid cover size");
                String type = response.header("Content-Type", "").toLowerCase(Locale.ROOT);
                if (!type.startsWith("image/")) throw new IOException("Not an image response");
                ByteArrayOutputStream bytes = new ByteArrayOutputStream(32 * 1024);
                try (InputStream stream = body.byteStream()) {
                    byte[] buffer = new byte[16 * 1024];
                    int count;
                    while ((count = stream.read(buffer)) != -1) {
                        checkCancelled();
                        if (bytes.size() + count > MAX_BYTES) throw new IOException("Cover is too large");
                        bytes.write(buffer, 0, count);
                    }
                }
                checkCancelled();
                if (bytes.size() == 0 || (body.contentLength() >= 0 && body.contentLength() != bytes.size())) {
                    throw new IOException("Incomplete cover response");
                }
                return bytes.toByteArray();
            } finally {
                call = null;
            }
        }
        throw new IOException("Too many cover redirects");
    }

    void cancel() {
        cancelled = true;
        Call current = call;
        if (current != null) current.cancel();
    }

    private void checkCancelled() throws InterruptedIOException {
        if (cancelled || Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Cover cancelled");
    }
}
