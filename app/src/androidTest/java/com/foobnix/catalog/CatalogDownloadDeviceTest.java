package com.foobnix.catalog;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.work.WorkManager;

import com.foobnix.dao2.FileMeta;
import com.foobnix.model.AppSP;
import com.foobnix.model.AppState;
import com.foobnix.pdf.info.ExtUtils;
import com.foobnix.ui2.AppDB;
import com.foobnix.ui2.MainTabs2;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.ebookdroid.ui.viewer.VerticalViewActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

/**
 * Deterministic Android platform checks, plus one explicitly opt-in live public-domain download.
 * Run live transfer with instrumentation argument {@code catalogLive=true}. No production hooks.
 */
@RunWith(AndroidJUnit4.class)
public class CatalogDownloadDeviceTest {
    private static final String FB2="<?xml version=\"1.0\" encoding=\"UTF-8\"?><FictionBook xmlns=\"http://www.gribuser.ru/xml/fictionbook/2.0\"><description/><body><section><p>Проверка файла</p></section></body></FictionBook>";
    private Context context;
    private File fixtures;
    private final List<String> taskIds=new ArrayList<>();
    private final List<String> localPaths=new ArrayList<>();
    private Activity activity;
    private VerticalViewActivity reader;
    private boolean readingPreferencesChanged, rememberedMode, preferredMode;
    private int readingMode;

    @Before public void setUp() {
        context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        fixtures=new File(context.getCacheDir(),"catalog-device-test-"+UUID.randomUUID());
        assertTrue(fixtures.mkdirs());
    }

    @After public void cleanOwnedFixturesAndTasks() throws Exception {
        CatalogDownloads downloads=CatalogDownloads.get(context);
        for(String id:taskIds) {
            downloads.cancel(id);
            WorkManager.getInstance(context).cancelUniqueWork(CatalogDownloads.workName(id))
                    .getResult().get(10,TimeUnit.SECONDS);
            CatalogDownloads.Task stored=downloads.task(id);
            if(stored!=null && !stored.localPath.isEmpty() && !localPaths.contains(stored.localPath)) localPaths.add(stored.localPath);
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        if(reader!=null) InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> reader.finish());
        if(activity!=null) InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> activity.finish());
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        if(readingPreferencesChanged) InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            AppState.get().isRememberMode=rememberedMode;
            AppState.get().isPrefFormatMode=preferredMode;
            AppSP.get().readingMode=readingMode;
        });
        // These paths were created by the opt-in test and never point at pre-existing books.
        for(String path:localPaths) {
            AppDB.get().deleteBy(path);
            new File(path).delete();
        }
        try(CatalogDownloadStore store=new CatalogDownloadStore(context)) {
            for(String id:taskIds) store.getWritableDatabase().delete("tasks","id=?",new String[]{id});
        }
        File[] files=fixtures.listFiles();
        if(files!=null) for(File f:files)f.delete();
        fixtures.delete();
    }

    @Test public void androidSaxAcceptsFb2AndUtf16() throws Exception {
        BookFileValidator.validate(raw("utf8.fb2",FB2.getBytes(StandardCharsets.UTF_8)),"FB2");
        BookFileValidator.validate(raw("utf16.fb2",FB2.replace("UTF-8","UTF-16").getBytes(StandardCharsets.UTF_16)),"FB2");
    }

    @Test public void androidSaxRejectsDoctypeAndTruncatedXml() throws Exception {
        rejected(raw("external.fb2","<!DOCTYPE FictionBook [<!ENTITY x SYSTEM 'file:///proc/version'>]><FictionBook>&x;</FictionBook>".getBytes(StandardCharsets.UTF_8)),"FB2");
        rejected(raw("internal.fb2","<!DOCTYPE FictionBook [<!ENTITY x 'expanded'>]><FictionBook>&x;</FictionBook>".getBytes(StandardCharsets.UTF_8)),"FB2");
        rejected(raw("truncated.fb2","<FictionBook><body>".getBytes(StandardCharsets.UTF_8)),"FB2");
    }

    @Test public void androidValidatesArchiveAndRejectsHtmlPayload() throws Exception {
        File zipped=new File(fixtures,"sample.fb2.zip");
        try(ZipOutputStream out=new ZipOutputStream(new FileOutputStream(zipped))) {
            out.putNextEntry(new ZipEntry("sample.fb2"));
            out.write(FB2.getBytes(StandardCharsets.UTF_8));out.closeEntry();
        }
        BookFileValidator.validate(zipped,"FB2.ZIP");
        rejected(raw("error.epub","<!DOCTYPE html><html>Temporarily unavailable</html>".getBytes(StandardCharsets.UTF_8)),"EPUB");
        rejected(raw("error.pdf","<html>Authorization required</html>".getBytes(StandardCharsets.UTF_8)),"PDF");
    }

    @Test public void downloadRedirectAcceptsOnlyExplicitHttpsOrigins() throws Exception {
        okhttp3.HttpUrl origin=CatalogDownloadWorker.checkedUrl("https://flibusta.is/b/72605/fb2");
        assertEquals("https://static.flibusta.is/b.fb2/book.fb2.zip",
                CatalogDownloadWorker.checkedRedirect(origin,"https://static.flibusta.is:443/b.fb2/book.fb2.zip").toString());
        assertEquals("https://flibusta.is/b/1/download",
                CatalogDownloadWorker.checkedRedirect(origin,"http://flibusta.is/b/1/download").toString());
        String[] rejected={"https://static.flibusta.is.evil.example/book", "https://evil.example/book",
                "https://user:secret@static.flibusta.is/book", "https://static.flibusta.is:8443/book",
                "http://static.flibusta.is:8080/book", "file:///sdcard/book", null};
        for(String target:rejected) {
            try { CatalogDownloadWorker.checkedRedirect(origin,target);fail("Unsafe redirect accepted: "+target); }
            catch(IOException expected) { assertEquals("UNTRUSTED_URL",expected.getMessage()); }
        }
    }

    @Test public void queueSurvivesClosingAndReopeningItsDatabase() {
        CatalogDownloads.Task t=fixtureTask(CatalogDownloads.QUEUED);
        t.bytes=42;t.total=512;t.pendingUri="content://example.test/owned-unfinished";
        t.cleanupUris="[\"content://example.test/owned-previous\"]";
        try(CatalogDownloadStore store=new CatalogDownloadStore(context)) { store.put(t); }
        try(CatalogDownloadStore reopened=new CatalogDownloadStore(context)) {
            CatalogDownloads.Task persisted=reopened.get(t.id);
            assertNotNull(persisted);assertEquals(t.generation,persisted.generation);
            assertEquals(42,persisted.bytes);assertEquals(512,persisted.total);
            assertEquals(t.pendingUri,persisted.pendingUri);assertEquals(t.cleanupUris,persisted.cleanupUris);
            assertEquals(CatalogDownloads.QUEUED,persisted.status);
        }
    }

    @Test public void orphanedRunningTaskBecomesRetryableFailure() {
        CatalogDownloads.Task t=fixtureTask(CatalogDownloads.RUNNING);
        // Represents a process that committed the queue record but never scheduled its work.
        t.created=System.currentTimeMillis()-60_000;
        try(CatalogDownloadStore store=new CatalogDownloadStore(context)) { store.put(t); }
        CatalogDownloads.Task recovered=find(CatalogDownloads.get(context).getTasks(),t.id);
        assertNotNull(recovered);assertEquals(CatalogDownloads.FAILED,recovered.status);
        assertEquals("SCHEDULER",recovered.errorCode);
    }

    @Test public void externallyRemovedBookLosesCompletedState() {
        CatalogDownloads.Task t=fixtureTask(CatalogDownloads.COMPLETED);
        t.localPath=new File(fixtures,"removed.fb2").getAbsolutePath();t.indexed=true;
        try(CatalogDownloadStore store=new CatalogDownloadStore(context)) { store.put(t); }
        CatalogDownloads.Task recovered=find(CatalogDownloads.get(context).getTasks(),t.id);
        assertNotNull(recovered);assertEquals(CatalogDownloads.FAILED,recovered.status);
        assertEquals("FILE_NOT_FOUND",recovered.errorCode);
        assertNull(CatalogDownloads.get(context).findCompleted(t.bookId));
    }

    @Test public void livePublicDomainDownloadPersistsAndRegistersInLibrary() throws Exception {
        assumeTrue("Opt in with -e catalogLive true",Boolean.parseBoolean(
                InstrumentationRegistry.getArguments().getString("catalogLive","false")));
        activity=InstrumentationRegistry.getInstrumentation().startActivitySync(
                new Intent(context,MainTabs2.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();

        CatalogEntry book=new CatalogEntry();
        book.id="device-test:white-nights:"+UUID.randomUUID();
        book.title="Белые ночи";book.author="Фёдор Достоевский";
        CatalogAcquisition format=new CatalogAcquisition();
        // The previously checked acquisition link for Dostoevsky's 1848 work.
        format.url="https://flibusta.is/b/72605/fb2";
        format.mimeType="application/fb2+zip";format.format="FB2.ZIP";
        CatalogDownloads downloads=CatalogDownloads.get(context);
        String id=downloads.enqueue(book,format,null);taskIds.add(id);
        CatalogDownloads.Task finished=null;
        long deadline=SystemClock.elapsedRealtime()+120_000;
        while(SystemClock.elapsedRealtime()<deadline) {
            finished=find(downloads.getTasks(),id);
            if(finished!=null && CatalogDownloads.COMPLETED.equals(finished.status)) break;
            if(finished!=null && CatalogDownloads.FAILED.equals(finished.status)) fail("Live download failed: "+finished.errorCode);
            SystemClock.sleep(300);
        }
        assertNotNull(finished);
        assertEquals("Live transfer did not complete before deadline",CatalogDownloads.COMPLETED,finished.status);
        localPaths.add(finished.localPath);
        File file=new File(finished.localPath);
        assertTrue(file.isFile());assertTrue(file.length()>0);assertEquals(file.length(),finished.bytes);
        BookFileValidator.validate(file,"FB2.ZIP");
        String generation=finished.generation;
        assertEquals(id,downloads.enqueue(book,format,null));
        try(CatalogDownloadStore reopened=new CatalogDownloadStore(context)) {
            CatalogDownloads.Task record=reopened.get(id);
            assertEquals(generation,record.generation);assertEquals(file.getPath(),record.localPath);
        }
        deadline=SystemClock.elapsedRealtime()+10_000;
        FileMeta meta=null;
        while(SystemClock.elapsedRealtime()<deadline) {
            downloads.getTasks();
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            meta=AppDB.get().load(file.getPath());
            if(meta!=null && Boolean.TRUE.equals(meta.getIsSearchBook())) break;
            SystemClock.sleep(100);
        }
        assertNotNull("Finished book registered in library",meta);
        assertTrue(Boolean.TRUE.equals(meta.getIsSearchBook()));
        assertEquals(book.title,meta.getTitle());
        assertEquals("fb2.zip",meta.getExt());
        assertNotNull(downloads.findCompleted(book.id));
        openDownloadedBookInExistingReader(meta,file);
    }

    private void openDownloadedBookInExistingReader(FileMeta meta,File file) throws Exception {
        Instrumentation instrumentation=InstrumentationRegistry.getInstrumentation();
        Instrumentation.ActivityMonitor monitor=instrumentation.addMonitor(VerticalViewActivity.class.getName(),null,false);
        try {
            instrumentation.runOnMainSync(() -> {
                rememberedMode=AppState.get().isRememberMode;preferredMode=AppState.get().isPrefFormatMode;
                readingMode=AppSP.get().readingMode;readingPreferencesChanged=true;
                AppState.get().isRememberMode=true;AppState.get().isPrefFormatMode=false;
                AppSP.get().readingMode=AppState.READING_MODE_SCROLL;
                ExtUtils.openFile(activity,meta);
            });
            Activity opened=instrumentation.waitForMonitorWithTimeout(monitor,30_000);
            assertNotNull("Existing reader opens the downloaded FB2 archive",opened);
            reader=(VerticalViewActivity)opened;
            assertEquals(file.getPath(),reader.getIntent().getData().getPath());
            java.util.concurrent.atomic.AtomicInteger pages=new java.util.concurrent.atomic.AtomicInteger();
            long deadline=SystemClock.elapsedRealtime()+60_000;
            while(SystemClock.elapsedRealtime()<deadline) {
                instrumentation.runOnMainSync(() -> pages.set(reader.getController().getDocumentModel().getPageCount()));
                if(pages.get()>0)break;
                SystemClock.sleep(250);
            }
            assertTrue("Reader decoded book pages",pages.get()>0);
        } finally { instrumentation.removeMonitor(monitor); }
    }

    private CatalogDownloads.Task fixtureTask(String status) {
        CatalogDownloads.Task t=new CatalogDownloads.Task();
        t.id="device-test-"+UUID.randomUUID();taskIds.add(t.id);t.bookId=t.id;
        t.generation=UUID.randomUUID().toString();t.workId=UUID.randomUUID().toString();
        t.title="Device fixture";t.format="FB2";t.status=status;t.created=System.currentTimeMillis();return t;
    }
    private static CatalogDownloads.Task find(List<CatalogDownloads.Task> tasks,String id) {
        for(CatalogDownloads.Task t:tasks) if(t.id.equals(id))return t;return null;
    }
    private File raw(String name,byte[] bytes) throws IOException {
        File file=new File(fixtures,name);try(FileOutputStream out=new FileOutputStream(file)){out.write(bytes);}return file;
    }
    private void rejected(File file,String format) throws Exception {
        try { BookFileValidator.validate(file,format);fail("Invalid bytes accepted as "+format); }
        catch(IOException expected) { assertNotNull(expected.getMessage()); }
    }
}
