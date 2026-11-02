package com.foobnix.catalog;

import android.content.SharedPreferences;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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

    @Test public void freshCacheAvoidsNetworkAndKeepsItsOriginalAge() throws Exception {
        List<String> requests = new ArrayList<>();
        CatalogRepository repository = repository(temporary.newFolder(), chain -> {
            requests.add(chain.request().url().toString());
            return response(chain.request(), 200, "application/atom+xml", FEED);
        });
        CatalogPage first = repository.loadFresh(null, false);
        CatalogPage immediate = repository.cached(null);
        CatalogPage next = repository.loadFresh(null, false);
        assertTrue(immediate.fromCache);
        assertTrue(next.fromCache);
        assertEquals(first.fetchedAt, immediate.fetchedAt);
        assertEquals(first.fetchedAt, next.fetchedAt);
        assertEquals(1, requests.size());
        assertTrue(CatalogRepository.isFresh(next));
        next.fetchedAt = System.currentTimeMillis() - CatalogRepository.FRESH_FOR_MS - 1;
        assertFalse(CatalogRepository.isFresh(next));
    }

    private static String books(String id, String next) {
        return "<feed xmlns='http://www.w3.org/2005/Atom'><title>Books</title>"
                + (next.isEmpty() ? "" : "<link rel='next' type='application/atom+xml' href='" + next + "'/>")
                + "<entry><id>" + id + "</id><title>Book " + id + "</title>"
                + "<link rel='http://opds-spec.org/acquisition' type='application/epub+zip' href='/b/1/epub'/>"
                + "</entry></feed>";
    }

    @Test public void warmupPreparesBookLandingAndOneNextPageWithoutDownloadingBooks() throws Exception {
        List<String> paths = new ArrayList<>();
        CatalogRepository repository = repository(temporary.newFolder(), chain -> {
            String path = chain.request().url().encodedPath(); paths.add(path);
            String xml = path.equals("/opds") ? FEED
                    : path.equals("/opds/new") ? books("first", "/opds/new?page=2") : books("second", "");
            if (chain.request().url().query() != null) xml = books("second", "");
            return response(chain.request(), 200, "application/atom+xml", xml);
        });
        CatalogPage home = repository.warmLanding();
        assertEquals("first", home.entries.get(0).id);
        assertEquals(3, paths.size());
        assertTrue(paths.stream().allMatch(path -> path.startsWith("/opds")));
        CatalogPage immediate = repository.cachedLanding();
        assertEquals(home.fetchedAt, immediate.fetchedAt);
        assertTrue(immediate.fromCache);
        assertEquals("second", repository.cached(home.nextUrl).entries.get(0).id);
        repository.warmLanding();
        assertEquals("Repeated warmup must use fresh metadata", 3, paths.size());
    }

    @Test public void landingRefreshRediscoversChangedDatedFeed() throws Exception {
        AtomicBoolean tomorrow = new AtomicBoolean(false);
        CatalogRepository repository = repository(temporary.newFolder(), chain -> {
            String day = tomorrow.get() ? "day-two" : "day-one";
            String xml = chain.request().url().encodedPath().equals("/opds")
                    ? FEED.replace("/opds/new", "/opds/new/" + day) : books(day, "");
            return response(chain.request(), 200, "application/atom+xml", xml);
        });
        assertEquals("day-one", repository.loadLanding(false).entries.get(0).id);
        tomorrow.set(true);
        assertEquals("day-one", repository.cachedLanding().entries.get(0).id);
        assertEquals("day-two", repository.loadLanding(false).entries.get(0).id);
        assertEquals("day-two", repository.cachedLanding().entries.get(0).id);
    }

    @Test public void landingNavigationIsBoundedAndDoesNotFollowCycles() throws Exception {
        List<String> paths = new ArrayList<>();
        CatalogRepository repository = repository(temporary.newFolder(), chain -> {
            paths.add(chain.request().url().encodedPath());
            return response(chain.request(), 200, "application/atom+xml", FEED);
        });
        repository.warmLanding();
        assertEquals(2, paths.size());
        assertEquals("/opds", paths.get(0));
        assertEquals("/opds/new", paths.get(1));

        paths.clear();
        CatalogRepository endless = repository(temporary.newFolder(), chain -> {
            paths.add(chain.request().url().encodedPath());
            String next = "/opds/new/" + paths.size();
            return response(chain.request(), 200, "application/atom+xml", FEED.replace("/opds/new", next));
        });
        endless.warmLanding();
        assertEquals("Root plus at most three navigation pages", 4, paths.size());
    }

    @Test public void repeatedSearchReusesDiscoveryMetadataAndSupportsImmediateSavedResult() throws Exception {
        List<String> addresses = new ArrayList<>();
        CatalogRepository repository = repository(temporary.newFolder(), chain -> {
            addresses.add(chain.request().url().toString());
            return response(chain.request(), 200, "application/atom+xml", FEED);
        });
        repository.search("one", false);
        repository.search("two", false);
        assertTrue(repository.cachedSearch("one").fromCache);
        assertEquals("One root discovery and two queries", 3, addresses.size());
    }

    @Test public void freshRootCanFetchMissingOpenSearchDescriptorAndThenReuseIt() throws Exception {
        List<String> paths = new ArrayList<>();
        String root = "<feed xmlns='http://www.w3.org/2005/Atom'><title>Catalog</title>"
                + "<link rel='search' type='application/opensearchdescription+xml' href='/opds-opensearch.xml'/></feed>";
        String descriptor = "<OpenSearchDescription xmlns='http://a9.com/-/spec/opensearch/1.1/'>"
                + "<Url type='application/atom+xml' template='https://flibusta.is/opds/search?q={searchTerms}'/>"
                + "</OpenSearchDescription>";
        CatalogRepository repository = repository(temporary.newFolder(), chain -> {
            String path = chain.request().url().encodedPath(); paths.add(path);
            return response(chain.request(), 200, "application/xml", path.equals("/opds") ? root
                    : path.equals("/opds-opensearch.xml") ? descriptor : books("found", ""));
        });
        repository.load(null, false);
        repository.search("one", false);
        repository.search("two", false);
        assertEquals(4, paths.size());
        assertEquals(1, paths.stream().filter("/opds-opensearch.xml"::equals).count());
        assertEquals("found", repository.cachedSearch("one").entries.get(0).id);
    }

    @Test public void disablingSpeculationPreventsAnyNetworkRequest() throws Exception {
        List<String> paths = new ArrayList<>();
        CatalogRepository repository = repository(temporary.newFolder(), chain -> {
            paths.add(chain.request().url().encodedPath());
            return response(chain.request(), 200, "application/atom+xml", FEED);
        });
        repository.setNetworkAllowed(() -> false);
        try { repository.warmLanding(); fail("Offline/unmetered guard must stop speculative requests"); }
        catch (IOException expected) { }
        assertTrue(paths.isEmpty());
    }

    @Test public void cachedSearchDiscoveryNeverBypassesTheExplicitMirror() throws Exception {
        List<String> hosts = new ArrayList<>();
        String root = "<feed xmlns='http://www.w3.org/2005/Atom'><title>Catalog</title>"
                + "<link rel='search' type='application/opensearchdescription+xml' href='/opds-opensearch.xml'/></feed>";
        String descriptor = "<OpenSearchDescription xmlns='http://a9.com/-/spec/opensearch/1.1/'>"
                + "<Url type='application/atom+xml' template='https://flibusta.is/opds/search?q={searchTerms}'/>"
                + "</OpenSearchDescription>";
        CatalogRepository repository = repository(temporary.newFolder(), chain -> {
            hosts.add(chain.request().url().host());
            String path = chain.request().url().encodedPath();
            return response(chain.request(), 200, "application/xml", path.equals("/opds") ? root
                    : path.equals("/opds-opensearch.xml") ? descriptor : books("found", ""));
        });
        repository.load(null, false);
        repository.setMirror("https://flibusta.site/");
        repository.search("one", false);
        assertEquals(3, hosts.size());
        assertEquals("flibusta.is", hosts.get(0));
        assertEquals("flibusta.site", hosts.get(1));
        assertEquals("flibusta.site", hosts.get(2));
    }

    @Test public void parsedCacheReturnsIndependentModelsIncludingAcquisitionLists() throws Exception {
        CatalogRepository repository = repository(temporary.newFolder(), chain ->
                response(chain.request(), 200, "application/atom+xml", books("original", "/opds/next")));
        CatalogPage online = repository.load(null, false);
        long fetched = online.fetchedAt;
        online.title = "Changed";
        online.entries.get(0).acquisitions.get(0).url = "https://example.test/changed";
        online.entries.clear();
        CatalogPage cached = repository.cached(null);
        assertEquals("original", cached.entries.get(0).id);
        assertEquals("https://flibusta.is/b/1/epub", cached.entries.get(0).acquisitions.get(0).url);
        cached.entries.get(0).title = "Changed again";
        cached.entries.get(0).acquisitions.clear();
        cached.fromCache = false; cached.fetchedAt = 1;
        CatalogPage again = repository.cached(null);
        assertEquals("Book original", again.entries.get(0).title);
        assertEquals(1, again.entries.get(0).acquisitions.size());
        assertTrue(again.fromCache);
        assertEquals(fetched, again.fetchedAt);
    }

    @Test public void parsedCacheDoesNotLeakAcrossDirectoriesAndTracksDiskRemoval() throws Exception {
        File firstDirectory = temporary.newFolder(), secondDirectory = temporary.newFolder();
        CatalogRepository first = repository(firstDirectory, chain ->
                response(chain.request(), 200, "application/atom+xml", books("first", "")));
        CatalogRepository second = repository(secondDirectory, chain ->
                response(chain.request(), 200, "application/atom+xml", books("second", "")));
        first.load(null, false); second.load(null, false);
        assertEquals("first", first.cached(null).entries.get(0).id);
        assertEquals("second", second.cached(null).entries.get(0).id);
        for (File file : firstDirectory.listFiles()) assertTrue(file.delete());
        try { first.cached(null); fail("A deleted disk entry must invalidate its parsed copy"); }
        catch (IOException expected) { }
        assertEquals("second", second.cached(null).entries.get(0).id);
    }

    @Test public void changedDiskFileCannotBeHiddenByParsedCache() throws Exception {
        File directory = temporary.newFolder();
        CatalogRepository repository = repository(directory, chain ->
                response(chain.request(), 200, "application/atom+xml", books("original", "")));
        repository.load(null, false);
        File page = directory.listFiles()[0];
        try (FileOutputStream output = new FileOutputStream(page)) {
            output.write("corrupted".getBytes(StandardCharsets.UTF_8));
        }
        try { repository.cached(null); fail("A changed file must be validated again"); }
        catch (IOException expected) { }
    }

    @Test public void parsedCacheIsBoundedByPageCountAndEstimatedMetadataBytes() throws Exception {
        File directory = temporary.newFolder();
        CatalogRepository repository = repository(directory, chain ->
                response(chain.request(), 200, "application/atom+xml", books(chain.request().url().encodedPath(), "")));
        for (int i = 0; i < 9; i++) repository.load("https://flibusta.is/opds/page/" + i, false);
        Field entriesField = CatalogRepository.class.getDeclaredField("PARSED"); entriesField.setAccessible(true);
        Field bytesField = CatalogRepository.class.getDeclaredField("parsedBytes"); bytesField.setAccessible(true);
        Map<?, ?> entries = (Map<?, ?>) entriesField.get(null);
        assertEquals(8, entries.size());
        byte[] digest = MessageDigest.getInstance("SHA-256").digest("/opds/page/0".getBytes(StandardCharsets.UTF_8));
        StringBuilder filename = new StringBuilder();
        for (byte value : digest) filename.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        assertFalse("Oldest parsed page is evicted", entries.containsKey(new File(directory, filename + ".feed").getCanonicalPath()));
        StringBuilder feed = new StringBuilder("<feed xmlns='http://www.w3.org/2005/Atom'>");
        char[] text = new char[16000]; java.util.Arrays.fill(text, 'x');
        for (int i = 0; i < 50; i++) feed.append("<entry><id>").append(i).append("</id><title>Book</title><summary>")
                .append(text).append("</summary></entry>");
        feed.append("</feed>");
        CatalogRepository large = repository(directory, chain -> response(chain.request(), 200, "application/atom+xml", feed.toString()));
        for (int i = 0; i < 3; i++) large.load("https://flibusta.is/opds/large/" + i, false);
        assertTrue("Metadata weight has its own bound", bytesField.getLong(null) <= 4L * 1024 * 1024);
        assertTrue("Byte budget evicts pages before reaching the count limit", entries.size() < 8);
    }
}
