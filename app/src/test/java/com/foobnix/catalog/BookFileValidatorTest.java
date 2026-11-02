package com.foobnix.catalog;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import static org.junit.Assert.*;

public class BookFileValidatorTest {
    @Rule public TemporaryFolder temp=new TemporaryFolder();
    private static final String FB2="<?xml version=\"1.0\" encoding=\"UTF-8\"?><FictionBook xmlns=\"http://www.gribuser.ru/xml/fictionbook/2.0\"><description/><body><section><p>Книга</p></section></body></FictionBook>";

    @Test public void acceptsFb2XmlAndUtf16() throws Exception {
        BookFileValidator.validate(raw(FB2.getBytes(StandardCharsets.UTF_8)),"FB2");
        BookFileValidator.validate(raw(FB2.replace("UTF-8","UTF-16").getBytes(StandardCharsets.UTF_16)),"FB2");
    }
    @Test public void rejectsHtmlEvenWhenNamedPdf() throws Exception { reject(raw("<html>Access denied</html>".getBytes(StandardCharsets.UTF_8)),"PDF"); }
    @Test public void rejectsHtmlEvenWhenNamedFb2() throws Exception { reject(raw("<html><body>Not a book</body></html>".getBytes(StandardCharsets.UTF_8)),"FB2"); }
    @Test public void rejectsTruncatedFb2() throws Exception { reject(raw(FB2.substring(0,FB2.length()-12).getBytes(StandardCharsets.UTF_8)),"FB2"); }
    @Test public void rejectsExternalAndInternalEntities() throws Exception {
        reject(raw("<!DOCTYPE FictionBook [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><FictionBook>&x;</FictionBook>".getBytes(StandardCharsets.UTF_8)),"FB2");
        reject(raw("<!DOCTYPE FictionBook [<!ENTITY x 'test'>]><FictionBook>&x;</FictionBook>".getBytes(StandardCharsets.UTF_8)),"FB2");
    }
    @Test public void rejectsExcessiveXmlDepth() throws Exception {
        StringBuilder text=new StringBuilder("<FictionBook>");
        for(int i=0;i<260;i++)text.append("<section>");
        for(int i=0;i<260;i++)text.append("</section>");
        text.append("</FictionBook>");reject(raw(text.toString().getBytes(StandardCharsets.UTF_8)),"FB2");
    }
    @Test public void acceptsEpubWithPackage() throws Exception { BookFileValidator.validate(zip(epub()),"EPUB"); }
    @Test public void rejectsZipWithoutEpubStructure() throws Exception { reject(zip(Map.of("book.txt","not an epub")),"EPUB"); }
    @Test public void rejectsEpubMissingRootPackage() throws Exception {
        Map<String,String> files=epub();files.remove("book.opf");reject(zip(files),"EPUB");
    }
    @Test public void rejectsEpubContainerDtd() throws Exception {
        Map<String,String> files=epub();files.put("META-INF/container.xml","<!DOCTYPE container SYSTEM 'https://example.test/entity'><container/>");reject(zip(files),"EPUB");
    }
    @Test public void acceptsFb2Archive() throws Exception { BookFileValidator.validate(zip(Map.of("книга.fb2",FB2)),"FB2.ZIP"); }
    @Test public void rejectsArchiveWithMultipleBooks() throws Exception { reject(zip(Map.of("one.fb2",FB2,"two.fb2",FB2)),"FB2.ZIP"); }
    @Test public void rejectsArchiveTraversalAndWrongContent() throws Exception {
        reject(zip(Map.of("../book.fb2",FB2)),"FB2.ZIP");
        reject(zip(Map.of("book.fb2","<html>download error</html>")),"FB2.ZIP");
    }
    @Test public void rejectsCorruptZip() throws Exception { reject(raw(new byte[]{'P','K',3,4,0,0,0}),"EPUB"); }
    @Test public void acceptsPdfSignature() throws Exception { BookFileValidator.validate(raw("%PDF-1.7\n1 0 obj\nendobj\n%%EOF".getBytes(StandardCharsets.US_ASCII)),"PDF"); }
    @Test public void requiresPalmDatabaseSignatureForMobi() throws Exception {
        byte[] good=new byte[128];System.arraycopy("BOOKMOBI".getBytes(StandardCharsets.US_ASCII),0,good,60,8);
        BookFileValidator.validate(raw(good),"MOBI");good[60]='X';reject(raw(good),"MOBI");
    }
    @Test public void rejectsEmptyFileAndUnsupportedFormat() throws Exception {
        reject(raw(new byte[0]),"PDF");reject(raw(new byte[]{1}),"HTML");
        assertEquals("fb2.zip",BookFileValidator.extension(" FB2.ZIP "));
    }
    private File raw(byte[] bytes) throws Exception { File f=temp.newFile();Files.write(f.toPath(),bytes);return f; }
    private File zip(Map<String,String> files) throws Exception {
        File f=temp.newFile();try(ZipOutputStream out=new ZipOutputStream(new FileOutputStream(f))) {
            for(Map.Entry<String,String> entry:files.entrySet()) { out.putNextEntry(new ZipEntry(entry.getKey()));out.write(entry.getValue().getBytes(StandardCharsets.UTF_8));out.closeEntry(); }
        }return f;
    }
    private Map<String,String> epub() {
        Map<String,String> m=new LinkedHashMap<>();m.put("mimetype","application/epub+zip");
        m.put("META-INF/container.xml","<container xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\"><rootfiles><rootfile full-path=\"book.opf\"/></rootfiles></container>");
        m.put("book.opf","<package xmlns=\"http://www.idpf.org/2007/opf\"/>");return m;
    }
    private void reject(File file,String format) throws Exception {
        try { BookFileValidator.validate(file,format);fail("Expected rejection of "+format); }
        catch(IOException expected) { assertNotNull(expected.getMessage()); }
    }
}
