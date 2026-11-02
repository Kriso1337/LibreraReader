package com.foobnix.catalog;

import org.junit.Test;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.io.ObjectOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

public class CatalogParserTest {
    private static final String BASE = "https://flibusta.is/opds";
    private final CatalogParser parser = new CatalogParser();

    private CatalogPage parse(String xml) throws IOException {
        return parser.parse(xml.getBytes(StandardCharsets.UTF_8), BASE);
    }

    private static String feed(String body) {
        return "<feed xmlns='http://www.w3.org/2005/Atom'><title>Flibusta catalog</title>" + body + "</feed>";
    }

    @Test public void realRootNavigationAndSearchShape() throws Exception {
        // Structure/links verified against https://flibusta.is/opds on 2026-11-02.
        CatalogPage page = parse(feed("<link href='/opds-opensearch.xml' rel='search' type='application/opensearchdescription+xml'/>"
                + "<link href='/opds/search?searchTerm={searchTerms}' rel='search' type='application/atom+xml'/>"
                + "<entry><id>tag:root:new</id><title>Новинки</title><content type='text'>Новые поступления за неделю</content>"
                + "<link href='/opds/new' type='application/atom+xml;profile=opds-catalog'/></entry>"));
        assertEquals("Flibusta catalog", page.title);
        assertEquals("https://flibusta.is/opds/search?searchTerm={searchTerms}", page.searchTemplate);
        assertEquals("https://flibusta.is/opds-opensearch.xml", page.searchDescriptionUrl);
        assertEquals("Новинки", page.entries.get(0).title);
        assertEquals("https://flibusta.is/opds/new", page.entries.get(0).navigationUrl);
        assertTrue(page.entries.get(0).acquisitions.isEmpty());
    }

    @Test public void actualBookMimeTypesAndPagination() throws Exception {
        // Metadata shape of the public-domain Белые ночи, /b/72605; no book file downloaded.
        CatalogPage page = parse(feed("<link rel='next' href='/opds/search?searchTerm=test&amp;pageNumber=1' type='application/atom+xml'/>"
                + "<entry><title>Белые ночи</title><author><name>Достоевский Федор Михайлович</name></author>"
                + "<link rel='related' href='/opds/author/18736' type='application/atom+xml'/>"
                + "<content type='text/html'>&lt;p&gt;Формат: fb2&lt;/p&gt;&lt;script&gt;alert(1)&lt;/script&gt;</content>"
                + "<link href='/i/5/72605/cover.jpg' rel='http://opds-spec.org/thumbnail' type='image/jpeg'/>"
                + "<link href='/b/72605/fb2' rel='http://opds-spec.org/acquisition/open-access' type='application/fb2+zip'/>"
                + "<link href='/b/72605/epub' rel='http://opds-spec.org/acquisition/open-access' type='application/epub+zip'/>"
                + "<link href='/b/72605/html' rel='http://opds-spec.org/acquisition/open-access' type='application/html+zip'/>"
                + "<id>tag:book:79e4a57bceb463544ab94b17f9a73013</id></entry>"));
        CatalogEntry book = page.entries.get(0);
        assertEquals("Достоевский Федор Михайлович", book.author);
        assertEquals("FB2.ZIP", book.acquisitions.get(0).format);
        assertEquals("EPUB", book.acquisitions.get(1).format);
        assertEquals(2, book.acquisitions.size());
        assertEquals("", book.navigationUrl);
        assertEquals("Формат: fb2", book.summary);
        assertTrue(page.nextUrl.endsWith("searchTerm=test&pageNumber=1"));
        assertEquals("https://flibusta.is/i/5/72605/cover.jpg", book.coverUrl);
    }

    @Test public void handlesNamespacesXmlBaseAndMultipleAuthors() throws Exception {
        CatalogPage page = parse("<a:feed xmlns:a='http://www.w3.org/2005/Atom' xml:base='https://proxy.flibusta.is/opds/'>"
                + "<a:title>Catalog</a:title><a:entry xml:base='../b/42/'>"
                + "<a:title>A &amp; B</a:title><a:author><a:name>One</a:name></a:author><a:author><a:name>Two</a:name></a:author>"
                + "<a:summary type='xhtml'><div xmlns='http://www.w3.org/1999/xhtml'><p>Hello</p><p>world</p></div></a:summary>"
                + "<a:link rel='http://opds-spec.org/acquisition' href='epub' type='application/epub+zip'/></a:entry></a:feed>");
        CatalogEntry book = page.entries.get(0);
        assertEquals("A & B", book.title);
        assertEquals("One, Two", book.author);
        assertEquals("Hello world", book.summary);
        assertEquals("https://proxy.flibusta.is/b/42/epub", book.acquisitions.get(0).url);
        assertEquals("flibusta:/b/42/epub", book.id);
    }

    @Test public void rejectsUntrustedAndUnsupportedLinksWithoutAdvertisingThem() throws Exception {
        CatalogEntry book = parse(feed("<entry><id>book:1</id><title>Book</title>"
                + "<link rel='http://opds-spec.org/acquisition' href='https://flibusta.is.evil.test/b/1/epub' type='application/epub+zip'/>"
                + "<link rel='http://opds-spec.org/acquisition' href='file:///etc/passwd' type='application/pdf'/>"
                + "<link rel='http://opds-spec.org/acquisition/buy' href='/b/1/epub' type='application/epub+zip'/>"
                + "<link rel='http://opds-spec.org/acquisition' href='/b/1/unknown' type='application/octet-stream'/>"
                + "<link rel='http://opds-spec.org/image' href='javascript:alert(1)' type='image/jpeg'/></entry>"))
                .entries.get(0);
        assertTrue(book.acquisitions.isEmpty());
        assertEquals("", book.coverUrl);
    }

    @Test public void honorsOpenSearchZeroPageOffsetAndUpgradesKnownLegacyUrl() throws Exception {
        String template = parser.parseSearchDescription(("<OpenSearchDescription xmlns='http://a9.com/-/spec/opensearch/1.1/'>"
                + "<Url type='application/atom+xml' pageOffset='0' indexOffset='0' "
                + "template='http://flibusta.is/opds/opensearch?searchTerm={searchTerms}&amp;pageNumber={startPage?}'/>"
                + "</OpenSearchDescription>").getBytes(StandardCharsets.UTF_8), "https://flibusta.is/opds-opensearch.xml");
        assertEquals("https://flibusta.is/opds/opensearch?searchTerm={searchTerms}&pageNumber=0", template);
    }

    @Test public void emptyFeedIsAValidEmptySearchResult() throws Exception {
        assertTrue(parse(feed("")).entries.isEmpty());
    }

    @Test public void rejectsHtmlWrongNamespaceDtdEntitiesMalformedAndOversizedXml() throws Exception {
        for (String xml : new String[]{"<html><body>Login required</body></html>",
                "<feed><title>Fake</title></feed>",
                "<!DOCTYPE feed [<!ENTITY x SYSTEM 'file:///etc/passwd'>]>" + feed("<title>&x;</title>"),
                "<!DOCTYPE feed SYSTEM 'https://example.test/external'>" + feed(""),
                feed("<entry>"), "\u0000" + feed("")}) {
            try { parse(xml); fail("Accepted unsafe/malformed XML"); }
            catch (IOException expected) { }
        }
        try { parser.parse(new byte[CatalogParser.MAX_BYTES + 1], BASE); fail(); }
        catch (IOException expected) { }
        try { parser.parse(feed("").getBytes(StandardCharsets.UTF_16), BASE); fail(); }
        catch (IOException expected) { }
    }

    @Test public void limitsNestingEvenWithoutAnEntityDeclaration() throws Exception {
        StringBuilder body = new StringBuilder("<entry><id>x</id><title>Book</title><summary>");
        for (int i = 0; i < 60; i++) body.append("<span>");
        for (int i = 0; i < 60; i++) body.append("</span>");
        body.append("</summary></entry>");
        try { parse(feed(body.toString())); fail(); } catch (IOException expected) { }
    }

    @Test public void oversizedMetadataRemainsBoundedWhenSavedForRotation() throws Exception {
        String longText = new String(new char[24000]).replace('\0', 'Я');
        String longPath = new String(new char[3900]).replace('\0', 'a');
        StringBuilder entry = new StringBuilder("<entry><id>").append(longText).append("</id><title>")
                .append(longText).append("</title><summary>").append(longText).append("</summary>");
        for (int i = 0; i < 20; i++) entry.append("<author><name>").append(longText.substring(0, 2000)).append("</name></author>");
        entry.append("<link rel='http://opds-spec.org/image' href='/").append(longText).append("' type='image/jpeg'/>");
        for (int i = 0; i < 30; i++) entry.append("<link rel='http://opds-spec.org/acquisition' href='/b/")
                .append(longPath).append(i).append("' type='application/epub+zip;ignored=")
                .append(longText.substring(0, 3000)).append("'/>");
        entry.append("</entry>");
        CatalogEntry book = parse(feed(entry.toString())).entries.get(0);
        assertEquals(512, book.title.length());
        assertEquals(1024, book.author.length());
        assertEquals(16 * 1024, book.summary.length());
        assertTrue(book.id.length() <= 2048);
        assertEquals("", book.coverUrl);
        assertEquals(16, book.acquisitions.size());
        assertEquals("application/epub+zip", book.acquisitions.get(0).mimeType);
        assertTrue(book.acquisitions.get(0).url.length() <= 4096);
        ByteArrayOutputStream serialized = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(serialized)) { output.writeObject(book); }
        assertTrue("Selected-book saved state must stay comfortably below the Binder limit", serialized.size() < 128 * 1024);
        String other = feed(entry.toString().replace("</id>", "different</id>"));
        assertNotEquals(book.id, parse(other).entries.get(0).id);
    }
}
