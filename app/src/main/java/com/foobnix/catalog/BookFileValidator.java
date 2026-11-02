package com.foobnix.catalog;

import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.ext.LexicalHandler;
import org.xml.sax.helpers.DefaultHandler;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.Locale;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.xml.parsers.SAXParserFactory;

/** Validates the actual bytes before any downloaded file enters the local library. */
public final class BookFileValidator {
    public static final long MAX_BYTES = 512L * 1024 * 1024;
    private BookFileValidator() {}

    public static String extension(String format) {
        String value = format == null ? "" : format.trim().toLowerCase(Locale.ROOT);
        switch (value) {
            case "epub": case "fb2": case "fb2.zip": case "pdf":
            case "mobi": case "azw": case "azw3": case "djvu": return value;
            default: throw new IllegalArgumentException("UNSUPPORTED_FORMAT");
        }
    }

    public static void validate(File file, String format) throws IOException {
        if (!file.isFile() || file.length() == 0 || file.length() > MAX_BYTES) invalid();
        String ext;
        try { ext = extension(format); } catch (IllegalArgumentException e) { throw new IOException("UNSUPPORTED_FORMAT"); }
        byte[] header = new byte[128];
        int size;
        try (InputStream in = new FileInputStream(file)) { size = in.read(header); }
        String head = new String(header, 0, Math.max(0, size), StandardCharsets.ISO_8859_1);
        switch (ext) {
            case "epub": case "fb2.zip": validateZip(file, ext); break;
            case "fb2":
                try (InputStream in = new BufferedInputStream(new FileInputStream(file))) { parseXml(in, "FictionBook"); }
                break;
            case "pdf": if (!head.startsWith("%PDF-")) invalid(); break;
            case "mobi": case "azw": case "azw3":
                if (size < 78 || !head.substring(60, 68).equals("BOOKMOBI")) invalid();
                break;
            case "djvu":
                if (size < 16 || !head.startsWith("AT&TFORM") || !(head.substring(12, 16).equals("DJVU") || head.substring(12, 16).equals("DJVM"))) invalid();
                break;
            default: invalid();
        }
    }

    private static void validateZip(File file, String format) throws IOException {
        try (ZipFile zip = new ZipFile(file)) {
            if (zip.size() > 5000) invalid();
            Enumeration<? extends ZipEntry> entries = zip.entries();
            ZipEntry fb2 = null;
            long expanded = 0;
            byte[] buffer = new byte[32 * 1024];
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (!safeEntryName(entry.getName())) invalid();
                if (entry.isDirectory()) continue;
                if (entry.getSize() > MAX_BYTES || (entry.getCompressedSize() > 0 && entry.getSize() / entry.getCompressedSize() > 1000)) invalid();
                CRC32 crc = new CRC32();
                long size = 0;
                try (InputStream in = zip.getInputStream(entry)) {
                    int count;
                    while ((count = in.read(buffer)) != -1) {
                        if (Thread.currentThread().isInterrupted()) throw new IOException("CANCELLED");
                        expanded += count; size += count;
                        if (expanded > MAX_BYTES) invalid();
                        crc.update(buffer, 0, count);
                    }
                }
                if (entry.getSize() >= 0 && size != entry.getSize()) invalid();
                if (entry.getCrc() >= 0 && crc.getValue() != entry.getCrc()) invalid();
                if (entry.getName().toLowerCase(Locale.ROOT).endsWith(".fb2")) {
                    if (fb2 != null) invalid();
                    fb2 = entry;
                }
            }
            if (format.equals("epub")) {
                ZipEntry mime = zip.getEntry("mimetype"), container = zip.getEntry("META-INF/container.xml");
                if (mime == null || mime.getSize() != 20 || container == null || container.getSize() > 1024 * 1024) invalid();
                byte[] value = new byte[20];
                try (java.io.DataInputStream in = new java.io.DataInputStream(zip.getInputStream(mime))) { in.readFully(value); }
                if (!new String(value, StandardCharsets.US_ASCII).equals("application/epub+zip")) invalid();
                String rootfile;
                try (InputStream in = zip.getInputStream(container)) { rootfile = parseXml(in, "container"); }
                if (rootfile == null || !safeEntryName(rootfile) || zip.getEntry(rootfile) == null) invalid();
            } else {
                if (fb2 == null) invalid();
                try (InputStream in = zip.getInputStream(fb2)) { parseXml(in, "FictionBook"); }
            }
        }
    }

    private static boolean safeEntryName(String name) {
        if (name == null || name.isEmpty() || name.startsWith("/") || name.contains("\\") || name.contains(":")) return false;
        for (String part : name.split("/")) if (part.equals("..")) return false;
        return true;
    }

    private static String parseXml(InputStream in, String expectedRoot) throws IOException {
        try {
            SAXParserFactory factory = SAXParserFactory.newInstance();
            factory.setNamespaceAware(true);
            XMLReader reader = factory.newSAXParser().getXMLReader();
            XmlGuard handler = new XmlGuard(expectedRoot);
            // Lexical DTD rejection also rejects internal entities; never rely on defaults.
            reader.setProperty("http://xml.org/sax/properties/lexical-handler", handler);
            reader.setEntityResolver((publicId, systemId) -> { throw new SAXException("External entity"); });
            reader.setContentHandler(handler);
            reader.setErrorHandler(handler);
            reader.parse(new InputSource(in));
            if (!handler.sawRoot) invalid();
            return handler.rootfile;
        } catch (IOException e) { throw e; }
        catch (Exception e) { throw new IOException("INVALID_FILE", e); }
    }

    private static final class XmlGuard extends DefaultHandler implements LexicalHandler {
        final String expected;
        int depth, nodes;
        boolean sawRoot;
        String rootfile;
        XmlGuard(String expected) { this.expected = expected; }
        @Override public void startElement(String uri, String local, String name, Attributes attrs) throws SAXException {
            if (Thread.currentThread().isInterrupted()) throw new SAXException("Cancelled");
            if (++depth > 256 || ++nodes > 2_000_000) throw new SAXException("XML limit");
            if (!sawRoot) {
                if (!expected.equals(local)) throw new SAXException("Unexpected root");
                sawRoot = true;
            }
            if (expected.equals("container") && local.equals("rootfile") && rootfile == null) rootfile = attrs.getValue("full-path");
        }
        @Override public void endElement(String uri, String local, String name) { depth--; }
        @Override public void fatalError(org.xml.sax.SAXParseException e) throws SAXException { throw e; }
        @Override public void error(org.xml.sax.SAXParseException e) throws SAXException { throw e; }
        @Override public void startDTD(String name, String publicId, String systemId) throws SAXException { throw new SAXException("DOCTYPE forbidden"); }
        @Override public void endDTD() {}
        @Override public void startEntity(String name) {}
        @Override public void endEntity(String name) {}
        @Override public void startCDATA() {}
        @Override public void endCDATA() {}
        @Override public void comment(char[] ch, int start, int length) {}
    }

    private static void invalid() throws IOException { throw new IOException("INVALID_FILE"); }
}
