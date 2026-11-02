package com.foobnix.catalog;

import org.jsoup.Jsoup;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.Locale;

import javax.xml.parsers.SAXParserFactory;

/** Bounded, namespace-aware OPDS 1.x parser; no Android UI dependencies. */
public final class CatalogParser {
    public static final int MAX_BYTES = 2 * 1024 * 1024;
    private static final String ATOM = "http://www.w3.org/2005/Atom";
    private static final String OPEN_SEARCH = "http://a9.com/-/spec/opensearch/1.1/";

    public CatalogPage parse(byte[] xml, String responseUrl) throws IOException {
        Handler handler = read(xml, responseUrl, false);
        if (!handler.feed) throw new IOException("Expected an Atom catalog");
        return handler.page;
    }

    public String parseSearchDescription(byte[] xml, String responseUrl) throws IOException {
        Handler handler = read(xml, responseUrl, true);
        if (!handler.searchDocument || handler.page.searchTemplate.isEmpty()) {
            throw new IOException("Missing supported OpenSearch template");
        }
        return handler.page.searchTemplate;
    }

    private Handler read(byte[] bytes, String responseUrl, boolean search) throws IOException {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES) {
            throw new IOException("Empty or oversized catalog");
        }
        String xml;
        try {
            xml = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException error) {
            throw new IOException("Catalog is not UTF-8", error);
        }
        // Parse a character stream so an encoding declaration cannot reinterpret the
        // bytes after this check. NUL rejects UTF-16 and obfuscated declarations.
        String upper = xml.toUpperCase(Locale.ROOT);
        if (xml.indexOf('\0') >= 0 || upper.contains("<!DOCTYPE") || upper.contains("<!ENTITY")) {
            throw new IOException("DTD and entities are not allowed in a catalog");
        }
        Handler handler = new Handler(CatalogUrls.validate(responseUrl), search);
        try {
            SAXParserFactory factory = SAXParserFactory.newInstance();
            factory.setNamespaceAware(true);
            XMLReader reader = factory.newSAXParser().getXMLReader();
            // Android and desktop SAX implementations differ in supported features.
            // The preflight DTD rejection and rejecting entity resolver also enforce
            // this boundary on parsers that do not expose these feature switches.
            disable(reader, "http://xml.org/sax/features/external-general-entities");
            disable(reader, "http://xml.org/sax/features/external-parameter-entities");
            disable(reader, "http://apache.org/xml/features/nonvalidating/load-external-dtd");
            reader.setEntityResolver((publicId, systemId) -> { throw new SAXException("External entity rejected"); });
            reader.setContentHandler(handler);
            reader.setErrorHandler(handler);
            reader.parse(new InputSource(new StringReader(xml)));
            return handler;
        } catch (Exception error) {
            throw new IOException("Invalid catalog document", error);
        }
    }

    private static void disable(XMLReader reader, String feature) {
        try { reader.setFeature(feature, false); } catch (SAXException unsupported) { /* see preflight */ }
    }

    private static String plain(String text, int limit) {
        String value = Jsoup.parse(text == null ? "" : text).text().trim();
        return truncate(value, limit);
    }

    private static String truncate(String value, int limit) {
        if (value.length() <= limit) return value;
        if (Character.isHighSurrogate(value.charAt(limit - 1))) limit--;
        return value.substring(0, limit);
    }

    private static String boundedId(String id) {
        if (id.length() <= 2048) return id;
        // Identifiers cannot be truncated: two books may share a long prefix.
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(id.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder("catalog-id-sha256:");
            for (byte value : digest) result.append(String.format(Locale.ROOT, "%02x", value & 255));
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static String format(String mime) {
        String type = mime.toLowerCase(Locale.ROOT).split(";", 2)[0].trim();
        switch (type) {
            case "application/epub+zip": return "EPUB";
            case "application/fb2+zip": case "application/x-fictionbook+zip": return "FB2.ZIP";
            case "application/fb2": case "text/fb2+xml": case "application/x-fictionbook+xml": return "FB2";
            case "application/pdf": return "PDF";
            case "application/x-mobipocket-ebook": return "MOBI";
            case "application/vnd.amazon.ebook": return "AZW";
            case "application/x-mobi8-ebook": return "AZW3";
            case "image/vnd.djvu": case "image/x-djvu": case "application/djvu": return "DJVU";
            default: return "";
        }
    }

    private static final class Node {
        final String name, namespace, base;
        final StringBuilder text = new StringBuilder();
        Node(String name, String namespace, String base) {
            this.name = name; this.namespace = namespace; this.base = base;
        }
    }

    private static final class Handler extends DefaultHandler {
        final CatalogPage page = new CatalogPage();
        final ArrayDeque<Node> stack = new ArrayDeque<>();
        final boolean wantSearch;
        CatalogEntry entry;
        boolean feed, searchDocument;
        int elements;

        Handler(String responseUrl, boolean search) { page.url = responseUrl; wantSearch = search; }

        @Override public void startElement(String uri, String localName, String qName, Attributes attributes) throws SAXException {
            if (++elements > 20000 || stack.size() >= 48) throw new SAXException("Catalog complexity limit");
            String base = stack.isEmpty() ? page.url : stack.peek().base;
            String xmlBase = attributes.getValue("http://www.w3.org/XML/1998/namespace", "base");
            try { if (xmlBase != null) base = CatalogUrls.resolve(base, xmlBase); }
            catch (IOException invalid) { throw new SAXException(invalid); }
            if (stack.isEmpty()) {
                feed = "feed".equals(localName) && ATOM.equals(uri);
                searchDocument = "OpenSearchDescription".equals(localName) && OPEN_SEARCH.equals(uri);
                if (wantSearch ? !searchDocument : !feed) throw new SAXException("Unexpected document root");
            }
            Node parent = stack.peek();
            stack.push(new Node(localName, uri, base));
            if (ATOM.equals(uri) && "entry".equals(localName) && stack.size() == 2) {
                if (page.entries.size() >= 1000) throw new SAXException("Too many catalog entries");
                entry = new CatalogEntry();
            }
            if (ATOM.equals(uri) && "link".equals(localName) && parent != null
                    && ((stack.size() == 2 && "feed".equals(parent.name))
                    || (stack.size() == 3 && "entry".equals(parent.name)))) {
                link(attributes, base, stack.size() == 2);
            }
            if (wantSearch && OPEN_SEARCH.equals(uri) && "Url".equals(localName)) {
                String type = value(attributes, "type");
                if (type.toLowerCase(Locale.ROOT).startsWith("application/atom+xml")) {
                    try {
                        String template = value(attributes, "template")
                                .replace("{startPage?}", offset(attributes, "pageOffset"))
                                .replace("{startIndex?}", offset(attributes, "indexOffset"));
                        page.searchTemplate = CatalogUrls.resolveTemplate(base, template);
                    }
                    catch (IOException ignored) { /* try another advertised template */ }
                }
            }
        }

        private void link(Attributes attributes, String base, boolean top) {
            String rel = value(attributes, "rel"), type = value(attributes, "type"), href = value(attributes, "href");
            String lowerType = type.toLowerCase(Locale.ROOT);
            try {
                if (top && "search".equals(rel)) {
                    if (lowerType.startsWith("application/atom+xml")) page.searchTemplate = CatalogUrls.resolveTemplate(base, href);
                    else if (lowerType.startsWith("application/opensearchdescription+xml")) page.searchDescriptionUrl = CatalogUrls.resolve(base, href);
                    return;
                }
                String url = CatalogUrls.resolve(base, href);
                if (top) {
                    if ("next".equals(rel) && CatalogUrls.isCatalogUrl(url)) page.nextUrl = url;
                    return;
                }
                if (entry == null) return;
                if (rel.equals("http://opds-spec.org/acquisition") || rel.equals("http://opds-spec.org/acquisition/open-access")) {
                    String format = format(type);
                    if (!format.isEmpty()) {
                        for (CatalogAcquisition item : entry.acquisitions) if (item.url.equals(url)) return;
                        if (entry.acquisitions.size() >= 16) return;
                        CatalogAcquisition acquisition = new CatalogAcquisition();
                        acquisition.url = url;
                        acquisition.mimeType = type.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
                        acquisition.format = format;
                        entry.acquisitions.add(acquisition);
                    }
                } else if (rel.equals("http://opds-spec.org/image") || rel.equals("http://opds-spec.org/image/thumbnail")
                        || rel.equals("http://opds-spec.org/thumbnail")) {
                    if (entry.coverUrl.isEmpty() || rel.endsWith("/thumbnail")) entry.coverUrl = url;
                } else if (!"related".equals(rel) && lowerType.startsWith("application/atom+xml") && CatalogUrls.isCatalogUrl(url)) {
                    if (entry.navigationUrl.isEmpty() || "alternate".equals(rel)) entry.navigationUrl = url;
                }
            } catch (IOException rejected) { /* unsafe or unsupported links are not actionable */ }
        }

        @Override public void characters(char[] characters, int start, int length) throws SAXException {
            if (!stack.isEmpty()) {
                Node node = stack.peek();
                if (node.text.length() + length > MAX_BYTES) throw new SAXException("Oversized catalog text");
                node.text.append(characters, start, length);
            }
        }

        @Override public void endElement(String uri, String localName, String qName) throws SAXException {
            Node node = stack.pop();
            Node parent = stack.peek();
            String text = node.text.toString();
            if (ATOM.equals(uri)) {
                if (parent != null && ATOM.equals(parent.namespace) && "feed".equals(parent.name) && stack.size() == 1 && "title".equals(localName)) page.title = plain(text, 512);
                if (entry != null && parent != null && ATOM.equals(parent.namespace) && "entry".equals(parent.name) && stack.size() == 2) {
                    switch (localName) {
                        case "id": entry.id = boundedId(text.trim()); break;
                        case "title": entry.title = plain(text, 512); break;
                        case "summary": entry.summary = plain(text, 16 * 1024); break;
                        case "content": if (entry.summary.isEmpty()) entry.summary = plain(text, 16 * 1024); break;
                    }
                }
                if (entry != null && "name".equals(localName) && parent != null && ATOM.equals(parent.namespace) && "author".equals(parent.name) && stack.size() == 3) {
                    String name = plain(text, 1024);
                    if (!name.isEmpty()) entry.author = truncate(entry.author + (entry.author.isEmpty() ? "" : ", ") + name, 1024);
                }
                if ("entry".equals(localName) && stack.size() == 1 && entry != null) {
                    if (entry.id.isEmpty()) {
                        String address = entry.navigationUrl.isEmpty()
                                ? (entry.acquisitions.isEmpty() ? "" : entry.acquisitions.get(0).url) : entry.navigationUrl;
                        try { if (!address.isEmpty()) entry.id = boundedId("flibusta:" + CatalogUrls.cacheKey(address)); }
                        catch (IOException ignored) { }
                    }
                    if (!entry.title.isEmpty() && !entry.id.isEmpty()) page.entries.add(entry);
                    entry = null;
                }
            }
            // Preserve textual XHTML summaries while ignoring active markup. The
            // final plain-text conversion strips escaped HTML and scripts as well.
            if (parent != null && !"feed".equals(parent.name) && !"entry".equals(parent.name)) {
                if (parent.text.length() + text.length() + 1 > MAX_BYTES) throw new SAXException("Oversized catalog text");
                parent.text.append(text).append(' ');
            }
        }

        private static String value(Attributes attributes, String key) {
            String value = attributes.getValue(key); return value == null ? "" : value;
        }

        private static String offset(Attributes attributes, String key) {
            String value = value(attributes, key);
            return value.matches("[0-9]{1,6}") ? value : "1";
        }
    }
}
