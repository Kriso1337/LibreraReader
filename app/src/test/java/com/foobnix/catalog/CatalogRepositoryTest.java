package com.foobnix.catalog;

import android.content.SharedPreferences;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import static org.junit.Assert.*;

/** Exercise the real repository with an in-process HTTP interceptor, never a live mirror. */
public class CatalogRepositoryTest {
    private static final String FEED = "<feed xmlns='http://www.w3.org/2005/Atom'><title>Catalog</title>"
            + "<link rel='search' type='application/atom+xml' href='/opds/search?q={searchTerms}'/>"
            + "<entry><id>tag:new</id><title>New</title><link type='application/atom+xml' href='/opds/new'/></entry></feed>";
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private CatalogRepository repository(File dir, Interceptor interceptor) {
        return new CatalogRepository(dir, settings(), new OkHttpClient.Builder()
                .followRedirects(false).followSslRedirects(false).addInterceptor(interceptor).build());
    }

    private static Response response(Request request, int status, String type, String body) {
        return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(status).message("test").header("Content-Type", type)
                .body(ResponseBody.create(MediaType.parse(type), body)).build();
    }

    private static SharedPreferences settings() {
        Map<String, String> data = new HashMap<>();
        SharedPreferences.Editor editor = (SharedPreferences.Editor) Proxy.newProxyInstance(
                CatalogRepositoryTest.class.getClassLoader(), new Class[]{SharedPreferences.Editor.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("putString")) { data.put((String) args[0], (String) args[1]); return proxy; }
                    if (method.getName().equals("commit")) return true;
                    return null;
                });
        return (SharedPreferences) Proxy.newProxyInstance(CatalogRepositoryTest.class.getClassLoader(),
                new Class[]{SharedPreferences.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getString")) return data.getOrDefault((String) args[0], (String) args[1]);
                    if (method.getName().equals("edit")) return editor;
                    return null;
                });
    }

    @Test public void savedPageIsAvailableOfflineWithoutANetworkCall() throws Exception {
        List<String> requests = new ArrayList<>();
        CatalogRepository repository = repository(temporary.newFolder(), chain -> {
            requests.add(chain.request().url().toString());
            return response(chain.request(), 200, "application/atom+xml;charset=utf-8", FEED);
        });
        CatalogPage fresh = repository.load(null, false);
        CatalogPage saved = repository.load(null, true);
        assertFalse(fresh.fromCache);
        assertTrue(saved.fromCache);
        assertEquals(fresh.fetchedAt, saved.fetchedAt);
        assertTrue(saved.fetchedAt > 0);
        assertEquals("New", saved.entries.get(0).title);
        assertEquals(1, requests.size());
    }

    @Test public void temporaryFailureTriesKnownMirrorsAndRemembersSuccessfulAddress() throws Exception {
        List<String> hosts = new ArrayList<>();
        CatalogRepository repository = repository(temporary.newFolder(), chain -> {
            String host = chain.request().url().host(); hosts.add(host);
            return response(chain.request(), host.equals("flibusta.is") ? 503 : 200, "application/atom+xml", FEED);
        });
        CatalogPage page = repository.load(null, false);
        assertEquals("https://proxy.flibusta.is/opds", page.url);
        assertEquals(2, hosts.size());
        repository.load("https://flibusta.is/opds/new", false);
        assertEquals("proxy.flibusta.is", hosts.get(2));
    }

    @Test public void explicitMirrorDoesNotSilentlySwitch() throws Exception {
        List<String> hosts = new ArrayList<>();
        CatalogRepository repository = repository(temporary.newFolder(), chain -> {
            hosts.add(chain.request().url().host());
            return response(chain.request(), 503, "application/atom+xml", "unavailable");
        });
        repository.setMirror("https://flibusta.site");
        assertEquals("https://flibusta.site/", repository.getMirror());
        try { repository.load(null, false); fail(); } catch (IOException expected) { }
        assertEquals(1, hosts.size());
        assertEquals("flibusta.site", hosts.get(0));
        repository.setMirror(null);
        assertEquals("", repository.getMirror());
    }

    @Test public void authenticationFailureDoesNotRetryOrPretendTheCacheIsFresh() throws Exception {
        List<String> requests = new ArrayList<>();
        CatalogRepository repository = repository(temporary.newFolder(), chain -> {
            requests.add(chain.request().url().toString());
            return response(chain.request(), 403, "text/html", "login required");
        });
        try { repository.load(null, false); fail(); }
        catch (CatalogRepository.HttpStatusException expected) { assertEquals(403, expected.statusCode); }
        assertEquals(1, requests.size());
    }

    @Test public void htmlWithSuccessStatusIsNeverCachedAsACatalog() throws Exception {
        CatalogRepository repository = repository(temporary.newFolder(), chain ->
                response(chain.request(), 200, "text/html", "<html>challenge</html>"));
        try { repository.load(null, false); fail(); } catch (IOException expected) { }
        try { repository.load(null, true); fail(); } catch (IOException expected) { }
    }

    @Test public void failedRefreshReturnsMarkedSavedPage() throws Exception {
        AtomicBoolean available = new AtomicBoolean(true);
        CatalogRepository repository = repository(temporary.newFolder(), chain -> {
            if (!available.get()) throw new IOException("network is offline");
            return response(chain.request(), 200, "application/atom+xml", FEED);
        });
        CatalogPage before = repository.load(null, false);
        available.set(false);
        CatalogPage after = repository.load(null, false);
        assertTrue(after.fromCache);
        assertEquals(before.fetchedAt, after.fetchedAt);
    }

    @Test public void redirectsAreValidatedAndCannotDowngradeHttps() throws Exception {
        for (String location : new String[]{"https://example.test/opds", "http://flibusta.is/opds", "https://flibusta.is/b/1/epub"}) {
            List<String> requests = new ArrayList<>();
            CatalogRepository repository = repository(temporary.newFolder(), chain -> {
                requests.add(chain.request().url().toString());
                return response(chain.request(), 302, "text/html", "").newBuilder().header("Location", location).build();
            });
            repository.setMirror(CatalogRepository.MIRRORS[0]);
            try { repository.load(null, false); fail(); } catch (IOException expected) { }
            assertEquals(1, requests.size());
        }
    }

    @Test public void finalResponseUrlResolvesRelativeLinksAndRedirectedPageIsCached() throws Exception {
        CatalogRepository repository = repository(temporary.newFolder(), chain -> {
            if (chain.request().url().encodedPath().equals("/opds")) {
                return response(chain.request(), 302, "text/html", "").newBuilder().header("Location", "/opds/").build();
            }
            return response(chain.request(), 200, "application/atom+xml", FEED.replace("href='/opds/new'", "href='new'"));
        });
        CatalogPage page = repository.load(null, false);
        assertEquals("https://flibusta.is/opds/", page.url);
        assertEquals("https://flibusta.is/opds/new", page.entries.get(0).navigationUrl);
        assertTrue(repository.load(null, true).fromCache);
    }

    @Test public void searchUsesAdvertisedTemplateAndEscapesTheQuery() throws Exception {
        List<String> addresses = new ArrayList<>();
        CatalogRepository repository = repository(temporary.newFolder(), chain -> {
            addresses.add(chain.request().url().toString());
            return response(chain.request(), 200, "application/atom+xml", FEED);
        });
        repository.search("Пушкин &?", false);
        assertEquals(2, addresses.size());
        assertTrue(addresses.get(1).contains("q=%D0%9F%D1%83%D1%88%D0%BA%D0%B8%D0%BD+%26%3F"));
    }
}
