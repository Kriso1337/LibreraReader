package com.foobnix.catalog;

import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import okio.BufferedSource;

import static org.junit.Assert.*;

public class CatalogCoverFetcherTest {
    private static final String COVER = "https://flibusta.is/i/5/72605/cover.jpg";

    private static Response.Builder response(Request request, int status, String type, byte[] bytes) {
        return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(status).message("test").header("Content-Type", type)
                .body(ResponseBody.create(MediaType.parse(type), bytes));
    }

    @Test public void followsOnlyValidatedHttpsRedirects() throws Exception {
        List<String> paths = new ArrayList<>();
        CatalogCoverFetcher fetcher = new CatalogCoverFetcher(new OkHttpClient.Builder().addInterceptor(chain -> {
            paths.add(chain.request().url().toString());
            assertNull(chain.request().header("Cookie"));
            assertNull(chain.request().header("Authorization"));
            if (paths.size() == 1) return response(chain.request(), 302, "text/html", new byte[0])
                    .header("Location", "https://proxy.flibusta.is/i/cover.jpg").build();
            return response(chain.request(), 200, "image/jpeg", new byte[]{1, 2, 3}).build();
        }).build());
        assertArrayEquals(new byte[]{1, 2, 3}, fetcher.fetch(COVER));
        assertEquals(2, paths.size());
        assertEquals("https://proxy.flibusta.is/i/cover.jpg", paths.get(1));
    }

    @Test public void rejectsUntrustedRedirectBeforeAnyFollowupRequest() throws Exception {
        for (String target : new String[]{"http://flibusta.is/i.jpg", "https://evil.test/i.jpg",
                "https://flibusta.is.evil.test/i.jpg", "https://user:secret@flibusta.is/i.jpg", "file:///etc/passwd"}) {
            AtomicInteger calls = new AtomicInteger();
            CatalogCoverFetcher fetcher = new CatalogCoverFetcher(new OkHttpClient.Builder().addInterceptor(chain -> {
                calls.incrementAndGet();
                return response(chain.request(), 302, "text/html", new byte[0]).header("Location", target).build();
            }).build());
            try { fetcher.fetch(COVER); fail(target); } catch (IOException expected) { }
            assertEquals(1, calls.get());
        }
    }

    @Test public void rejectsErrorAndNonImagePayloads() throws Exception {
        for (int status : new int[]{200, 401, 403, 404, 503}) {
            AtomicInteger calls = new AtomicInteger();
            CatalogCoverFetcher fetcher = new CatalogCoverFetcher(new OkHttpClient.Builder().addInterceptor(chain -> {
                calls.incrementAndGet();
                return response(chain.request(), status, "text/html", "Login".getBytes()).build();
            }).build());
            try { fetcher.fetch(COVER); fail(); } catch (IOException expected) { }
            assertEquals(1, calls.get());
        }
    }

    @Test public void boundsDeclaredAndUnknownLengthBodies() throws Exception {
        byte[] payload = new byte[CatalogCoverFetcher.MAX_BYTES + 1];
        for (boolean declared : new boolean[]{true, false}) {
            CatalogCoverFetcher fetcher = new CatalogCoverFetcher(new OkHttpClient.Builder().addInterceptor(chain -> {
                Response.Builder reply = response(chain.request(), 200, "image/png", payload);
                if (!declared) reply.body(new ResponseBody() {
                    @Override public MediaType contentType() { return MediaType.parse("image/png"); }
                    @Override public long contentLength() { return -1; }
                    @Override public BufferedSource source() { return new Buffer().write(payload); }
                });
                return reply.build();
            }).build());
            try { fetcher.fetch(COVER); fail(); } catch (IOException expected) { }
        }
    }

    @Test public void capsRedirectLoops() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CatalogCoverFetcher fetcher = new CatalogCoverFetcher(new OkHttpClient.Builder().addInterceptor(chain -> {
            calls.incrementAndGet();
            return response(chain.request(), 302, "text/html", new byte[0]).header("Location", COVER).build();
        }).build());
        try { fetcher.fetch(COVER); fail(); } catch (IOException expected) { }
        assertEquals(5, calls.get());
    }

    @Test public void cancelBeforeStartMakesNoRequest() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CatalogCoverFetcher fetcher = new CatalogCoverFetcher(new OkHttpClient.Builder().addInterceptor(chain -> {
            calls.incrementAndGet();
            return response(chain.request(), 200, "image/jpeg", new byte[]{1}).build();
        }).build());
        fetcher.cancel();
        try { fetcher.fetch(COVER); fail(); } catch (IOException expected) { }
        assertEquals(0, calls.get());
    }

}
