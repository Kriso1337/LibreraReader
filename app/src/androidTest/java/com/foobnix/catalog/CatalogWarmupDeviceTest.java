package com.foobnix.catalog;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.SystemClock;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.work.Data;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/** Metadata only: private test cache/prefs, no local books or production work-queue changes. */
@RunWith(AndroidJUnit4.class)
public class CatalogWarmupDeviceTest {
    private Context context;
    private File cache;
    private String preferenceName;
    private SharedPreferences prefs;

    @Before public void setup() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        preferenceName = "catalog-warmup-test-" + UUID.randomUUID();
        prefs = context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE);
        cache = new File(context.getCacheDir(), preferenceName);
        assertTrue(cache.mkdirs());
    }

    @After public void cleanup() {
        File[] files = cache.listFiles();
        if (files != null) for (File file : files) assertTrue(file.delete());
        assertTrue(cache.delete());
        context.deleteSharedPreferences(preferenceName);
    }

    @SuppressLint("RestrictedApi")
    @Test public void speculationUsesUnmeteredDeferredNonForegroundWork() {
        OneTimeWorkRequest request = CatalogWarmup.request(new Data.Builder()
                .putString(CatalogWarmup.MIRROR, "https://flibusta.is/").build());
        assertEquals(NetworkType.UNMETERED, request.getWorkSpec().constraints.getRequiredNetworkType());
        assertTrue(request.getWorkSpec().constraints.requiresBatteryNotLow());
        assertTrue(request.getWorkSpec().initialDelay >= 2000);
        assertFalse(request.getWorkSpec().expedited);
        assertEquals(CatalogPrefetchWorker.class.getName(), request.getWorkSpec().workerClassName);
    }

    @Test public void androidCacheIsImmediatelyReadableAfterRepositoryRecreation() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        String root = "<feed xmlns='http://www.w3.org/2005/Atom'><title>Catalog</title>"
                + "<entry><id>new</id><title>New</title><link type='application/atom+xml' href='/opds/new'/></entry></feed>";
        String books = "<feed xmlns='http://www.w3.org/2005/Atom'><title>Books</title>"
                + "<link rel='next' type='application/atom+xml' href='/opds/new?page=2'/>"
                + "<entry><id>book-one</id><title>Белые ночи</title><link rel='http://opds-spec.org/acquisition'"
                + " type='application/epub+zip' href='/b/1/epub'/></entry></feed>";
        OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain -> {
            requests.incrementAndGet();
            assertTrue(CatalogUrls.isCatalogUrl(chain.request().url().toString()));
            String xml = chain.request().url().encodedPath().equals("/opds") ? root : books;
            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("test").header("Content-Type", "application/atom+xml")
                    .body(ResponseBody.create(MediaType.parse("application/atom+xml"), xml)).build();
        }).build();
        CatalogRepository first = new CatalogRepository(cache, prefs, client);
        long start = SystemClock.elapsedRealtime();
        CatalogPage prepared = first.warmLanding();
        long prepareMs = SystemClock.elapsedRealtime() - start;
        assertEquals(3, requests.get());
        CatalogRepository recreated = new CatalogRepository(cache, prefs, client);
        recreated.setNetworkAllowed(() -> false);
        start = SystemClock.elapsedRealtime();
        CatalogPage immediate = recreated.cachedLanding();
        long cacheMs = SystemClock.elapsedRealtime() - start;
        assertTrue(immediate.fromCache);
        assertEquals(prepared.entries.get(0).id, immediate.entries.get(0).id);
        assertEquals(prepared.fetchedAt, immediate.fetchedAt);
        assertTrue(recreated.loadFresh(immediate.nextUrl, false).fromCache);
        assertEquals("Opening the saved home and next page must add zero HTTP calls", 3, requests.get());
        report("isolated", prepareMs, cacheMs, immediate.entries.size(), requests.get());
    }

    @Test public void workerNetworkGateStopsSpeculationBeforeHttp() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        OkHttpClient client = new OkHttpClient.Builder().addInterceptor(chain -> {
            requests.incrementAndGet();
            throw new IOException("Should not reach HTTP");
        }).build();
        CatalogRepository repository = new CatalogRepository(cache, prefs, client);
        repository.setNetworkAllowed(() -> false);
        try { repository.warmLanding(); fail("Disabled speculation must stop before HTTP"); }
        catch (IOException expected) { }
        assertEquals(0, requests.get());
    }

    @Test public void liveMetadataWarmupReportsColdAndCacheTimingWithoutBookDownloads() throws Exception {
        assumeTrue("Opt in with -e catalogLive true", Boolean.parseBoolean(
                InstrumentationRegistry.getArguments().getString("catalogLive", "false")));
        AtomicInteger requests = new AtomicInteger();
        OkHttpClient client = new OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS).callTimeout(25, TimeUnit.SECONDS)
                .followRedirects(false).followSslRedirects(false).addInterceptor(chain -> {
                    assertTrue("Only OPDS metadata may be prefetched", CatalogUrls.isCatalogUrl(chain.request().url().toString()));
                    requests.incrementAndGet();
                    return chain.proceed(chain.request());
                }).build();
        CatalogRepository repository = new CatalogRepository(cache, prefs, client);
        long start = SystemClock.elapsedRealtime();
        CatalogPage prepared = repository.warmLanding();
        long coldMs = SystemClock.elapsedRealtime() - start;
        assertTrue("The advertised new-books branch must reach books", CatalogRepository.hasBooks(prepared));
        int coldRequests = requests.get();
        CatalogRepository recreated = new CatalogRepository(cache, prefs, client);
        recreated.setNetworkAllowed(() -> false);
        start = SystemClock.elapsedRealtime();
        CatalogPage saved = recreated.cachedLanding();
        long cacheMs = SystemClock.elapsedRealtime() - start;
        assertTrue(saved.fromCache);
        assertEquals(prepared.entries.get(0).id, saved.entries.get(0).id);
        assertEquals(prepared.fetchedAt, saved.fetchedAt);
        assertEquals(coldRequests, requests.get());
        report("live", coldMs, cacheMs, saved.entries.size(), coldRequests);
    }

    private static void report(String kind, long coldMs, long cachedMs, int books, int requests) {
        Bundle output = new Bundle();
        output.putString("stream", "\nCatalog warmup " + kind + ": prepare=" + coldMs + " ms, cache="
                + cachedMs + " ms, books=" + books + ", HTTP prepare=" + requests + ", HTTP cache=0\n");
        InstrumentationRegistry.getInstrumentation().sendStatus(0, output);
    }
}
