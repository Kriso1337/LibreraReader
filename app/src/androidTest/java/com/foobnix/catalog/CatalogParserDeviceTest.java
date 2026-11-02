package com.foobnix.catalog;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

/** Exercise Android's SAX provider, which differs from the host JVM implementation. */
@RunWith(AndroidJUnit4.class)
public class CatalogParserDeviceTest {
    private final CatalogParser parser = new CatalogParser();

    @Test public void atomNamespacesXmlBaseAndRealAcquisitionFormats() throws Exception {
        String xml = "<a:feed xmlns:a='http://www.w3.org/2005/Atom' xml:base='https://proxy.flibusta.is/opds/'>"
                + "<a:title>Каталог</a:title><a:link rel='next' type='application/atom+xml' href='new?page=1'/>"
                + "<a:entry xml:base='../b/72605/'><a:id>tag:72605</a:id><a:title>Белые ночи</a:title>"
                + "<a:author><a:name>Федор Достоевский</a:name></a:author>"
                + "<a:content type='html'>&lt;p&gt;Описание&lt;/p&gt;&lt;script&gt;unsafe()&lt;/script&gt;</a:content>"
                + "<a:link rel='http://opds-spec.org/acquisition/open-access' type='application/fb2+zip' href='fb2'/>"
                + "<a:link rel='http://opds-spec.org/acquisition/open-access' type='application/epub+zip' href='epub'/>"
                + "</a:entry></a:feed>";
        CatalogPage page = parser.parse(xml.getBytes(StandardCharsets.UTF_8), "https://flibusta.is/opds");
        assertEquals("Каталог", page.title);
        assertEquals("https://proxy.flibusta.is/opds/new?page=1", page.nextUrl);
        CatalogEntry entry = page.entries.get(0);
        assertEquals("Описание", entry.summary);
        assertEquals("Федор Достоевский", entry.author);
        assertEquals("FB2.ZIP", entry.acquisitions.get(0).format);
        assertEquals("https://proxy.flibusta.is/b/72605/fb2", entry.acquisitions.get(0).url);
        assertEquals("EPUB", entry.acquisitions.get(1).format);
    }

    @Test public void rejectsDoctypeEntitiesHtmlAndUntrustedLinks() throws Exception {
        String atom = "<feed xmlns='http://www.w3.org/2005/Atom'><title>Catalog</title></feed>";
        for (String xml : new String[]{"<!DOCTYPE feed SYSTEM 'file:///etc/passwd'>" + atom,
                "<!DOCTYPE feed [<!ENTITY x SYSTEM 'https://evil.test/'>]>" + atom,
                "<html><body>Login</body></html>", "<feed><title>Wrong namespace</title></feed>"}) {
            try {
                parser.parse(xml.getBytes(StandardCharsets.UTF_8), "https://flibusta.is/opds");
                fail("Accepted unsafe XML");
            } catch (IOException expected) { }
        }
        String unsafe = "<feed xmlns='http://www.w3.org/2005/Atom'><entry><id>tag:test</id><title>Book</title>"
                + "<link rel='http://opds-spec.org/acquisition' type='application/epub+zip' href='https://evil.test/book'/>"
                + "</entry></feed>";
        assertTrue(parser.parse(unsafe.getBytes(StandardCharsets.UTF_8), "https://flibusta.is/opds")
                .entries.get(0).acquisitions.isEmpty());
    }
}
