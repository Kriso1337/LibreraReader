package com.foobnix.catalog;

import org.junit.Test;
import java.io.IOException;
import static org.junit.Assert.*;

public class CatalogUrlsTest {
    @Test public void resolvesLinksAgainstTheActualResponse() throws Exception {
        String base = "https://proxy.flibusta.is/opds/author/12?page=1";
        assertEquals("https://proxy.flibusta.is/opds/author/12?page=2", CatalogUrls.resolve(base, "?page=2"));
        assertEquals("https://proxy.flibusta.is/opds/new", CatalogUrls.resolve(base, "../new"));
        assertEquals("https://flibusta.site/b/42/epub", CatalogUrls.resolve(base, "//flibusta.site/b/42/epub"));
        assertEquals("https://flibusta.is/b/42/epub", CatalogUrls.resolve(base, "http://flibusta.is/b/42/epub"));
    }

    @Test public void rejectsCredentialsPortsLookalikesAndDowngradeRequests() throws Exception {
        for (String url : new String[]{"http://flibusta.is/opds", "https://flibusta.is.evil.test/opds",
                "https://user:password@flibusta.is/opds", "https://flibusta.is:444/opds",
                "javascript:alert(1)", "https://127.0.0.1/opds", "content://flibusta.is/opds"}) {
            try { CatalogUrls.validate(url); fail("Accepted " + url); } catch (IOException expected) { }
        }
    }

    @Test public void searchDoesNotTurnUserTextIntoUrlSyntax() throws Exception {
        String url = CatalogUrls.searchUrl("https://flibusta.is/opds/search?q={searchTerms}", "Ё &?/книга");
        assertEquals("https://flibusta.is/opds/search?q=%D0%81+%26%3F%2F%D0%BA%D0%BD%D0%B8%D0%B3%D0%B0", url);
        try { CatalogUrls.resolveTemplate("https://flibusta.is/opds", "/opds/search?q={searchTerms}&secret={password}"); fail(); }
        catch (IOException expected) { }
    }

    @Test public void mirrorRewritingIsOnlyForCatalogNavigation() throws Exception {
        assertEquals("https://flibusta.site/opds/search?q=abc", CatalogUrls.onMirror("https://flibusta.is/opds/search?q=abc", "https://flibusta.site/"));
        assertEquals(CatalogUrls.cacheKey("https://flibusta.is/opds/search?q=abc"), CatalogUrls.cacheKey("https://flibusta.site/opds/search?q=abc"));
        assertFalse(CatalogUrls.isCatalogUrl("https://flibusta.is/opds.evil"));
        try { CatalogUrls.onMirror("https://flibusta.is/b/42/epub", "https://flibusta.site/"); fail(); }
        catch (IOException expected) { }
    }

    @Test public void exactDownloadCdnIsSeparateFromCatalogAndAllUrlsAreBounded() throws Exception {
        String cdn = "https://static.flibusta.is/b.fb2/book.fb2.zip";
        assertEquals(cdn, CatalogUrls.validateDownload(cdn));
        assertEquals(cdn, CatalogUrls.resolveDownload("https://flibusta.is/b/72605/fb2", cdn));
        assertFalse(CatalogUrls.isCatalogUrl("https://static.flibusta.is/opds"));
        try { CatalogUrls.validate(cdn); fail(); } catch (IOException expected) { }
        for (String invalid : new String[]{"https://static.flibusta.is.evil.test/book", "https://user:secret@static.flibusta.is/book",
                "http://static.flibusta.is/book", "https://static.flibusta.is:444/book",
                "https://flibusta.is/" + new String(new char[4096]).replace('\0', 'a')}) {
            try { CatalogUrls.validateDownload(invalid); fail(invalid); } catch (IOException expected) { }
        }
    }
}
