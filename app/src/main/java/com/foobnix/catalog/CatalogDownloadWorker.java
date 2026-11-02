package com.foobnix.catalog;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.work.ForegroundInfo;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import com.foobnix.android.utils.JsonDB;
import com.foobnix.dao2.FileMeta;
import com.foobnix.model.AppProfile;
import com.foobnix.pdf.info.R;
import com.foobnix.pdf.info.model.BookCSS;
import com.foobnix.pdf.search.activity.msg.NotifyAllFragments;
import com.foobnix.sys.TempHolder;
import com.foobnix.ui2.AppDB;
import com.foobnix.ui2.MainTabs2;
import org.greenrobot.eventbus.EventBus;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.InterruptedIOException;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/** WorkManager owns execution; the queue and final files do not depend on the visible screen. */
public final class CatalogDownloadWorker extends Worker {
    private static final String CHANNEL="catalog-downloads";
    private static final OkHttpClient HTTP=new OkHttpClient.Builder()
            .connectTimeout(15,TimeUnit.SECONDS).readTimeout(45,TimeUnit.SECONDS)
            .callTimeout(8,TimeUnit.MINUTES).followRedirects(false).followSslRedirects(false).build();
    private volatile Call call;
    private volatile CatalogDownloads.Task running;
    private CatalogDownloads downloads;
    private long lastProgress;

    public CatalogDownloadWorker(@NonNull Context context,@NonNull WorkerParameters parameters) { super(context,parameters); }

    @NonNull @Override public Result doWork() {
        downloads=CatalogDownloads.get(getApplicationContext());
        String id=getInputData().getString(CatalogDownloads.KEY_ID), generation=getInputData().getString(CatalogDownloads.KEY_GENERATION);
        CatalogDownloads.Task t=id==null?null:downloads.task(id);
        if(t==null || !t.generation.equals(generation) || !CatalogDownloads.active(t.status)) return Result.success();
        running=t;
        File staging=new File(new File(getApplicationContext().getCacheDir(),"catalog-downloads"),t.id+"-"+t.generation+".part");
        boolean completed=false;
        try {
            cleanupPending(t);
            t.status=CatalogDownloads.RUNNING; t.bytes=0; t.total=-1; t.error=""; t.errorCode="";
            if(!downloads.update(t)) return Result.success();
            // A foreground worker is bounded by OS quotas; state is persisted for a restart.
            setForegroundAsync(foreground(t)).get();
            if(!staging.getParentFile().isDirectory() && !staging.getParentFile().mkdirs()) throw new DownloadFailure("NO_SPACE");
            transfer(t,staging);
            checkActive(t);
            BookFileValidator.validate(staging,t.format);
            checkActive(t);
            publishFile(t,staging);
            checkActive(t);
            t.status=CatalogDownloads.COMPLETED; t.bytes=staging.length(); t.total=staging.length();
            String ownedPath=t.pendingPath, ownedUri=t.pendingUri;
            t.localPath=ownedPath; t.localUri=ownedUri; t.pendingPath=""; t.pendingUri="";
            if(!downloads.update(t)) {
                // Cancellation or a newer retry won the race; this attempt owns only these files.
                t.pendingPath=ownedPath; t.pendingUri=ownedUri;
                throw new InterruptedIOException("CANCELLED");
            }
            completed=true;
            registerBook(getApplicationContext(),t);
            return Result.success();
        } catch(Exception e) {
            if(isStopped() || !downloads.isCurrent(t)) return Result.success();
            String code=e instanceof SecurityException?"STORAGE_ACCESS":e instanceof DownloadFailure?e.getMessage():
                    e instanceof IOException?("INVALID_FILE".equals(e.getMessage())||"UNSUPPORTED_FORMAT".equals(e.getMessage())?e.getMessage():"NETWORK"):"SCHEDULER";
            boolean retry="NETWORK".equals(code) || "SERVER".equals(code);
            t.status=retry&&getRunAttemptCount()<3?CatalogDownloads.WAITING_NETWORK:CatalogDownloads.FAILED;
            downloads.setError(t,code); downloads.update(t);
            return retry&&getRunAttemptCount()<3?Result.retry():Result.failure();
        } finally {
            if(!completed) cleanupPending(t);
            if(staging.isFile()) staging.delete();
            call=null; running=null;
        }
    }

    static HttpUrl checkedUrl(String value) {
        try { return HttpUrl.parse(CatalogUrls.validateDownload(value)); }
        catch(IOException e) { throw new IllegalArgumentException("UNTRUSTED_URL",e); }
    }

    static HttpUrl checkedRedirect(HttpUrl origin,String location) throws IOException {
        try { return HttpUrl.parse(CatalogUrls.resolveDownload(origin.toString(),location)); }
        catch(IOException e) { throw new DownloadFailure("UNTRUSTED_URL",e); }
    }

    private void transfer(CatalogDownloads.Task t,File staging) throws IOException {
        HttpUrl url=checkedUrl(t.url);
        for(int redirects=0;redirects<=4;redirects++) {
            checkActive(t);
            Request request=new Request.Builder().url(url).header("User-Agent","Librera-Catalog/1.0")
                    .header("Accept-Encoding","identity").build();
            call=HTTP.newCall(request);
            try(Response response=call.execute()) {
                int status=response.code();
                if(status==301||status==302||status==303||status==307||status==308) {
                    // The catalog serves books from its explicit HTTPS static origin.
                    // Revalidate every redirect; never send credentials or HTTP requests.
                    url=checkedRedirect(url,response.header("Location"));
                    continue;
                }
                if(status==429) throw new DownloadFailure("RATE_LIMITED");
                if(status>=500) throw new DownloadFailure("SERVER");
                if(status==401||status==403) throw new DownloadFailure("SOURCE_ACCESS");
                if(status==404||status==410) throw new DownloadFailure("FILE_NOT_FOUND");
                if(status!=200) throw new DownloadFailure("HTTP_ERROR");
                ResponseBody body=response.body();
                if(body==null) throw new DownloadFailure("INVALID_FILE");
                String mime=response.header("Content-Type","").toLowerCase(Locale.ROOT);
                if(mime.contains("text/html")||mime.contains("application/xhtml")||mime.contains("application/json")) throw new DownloadFailure("INVALID_FILE");
                t.total=body.contentLength();
                if(t.total>BookFileValidator.MAX_BYTES) throw new DownloadFailure("TOO_LARGE");
                if(t.total>0 && staging.getParentFile().getUsableSpace()<t.total+1024*1024) throw new DownloadFailure("NO_SPACE");
                try(InputStream in=body.byteStream();FileOutputStream out=new FileOutputStream(staging)) {
                    byte[] bytes=new byte[64*1024]; int count;
                    while((count=in.read(bytes))!=-1) {
                        checkActive(t);
                        t.bytes+=count;
                        if(t.bytes>BookFileValidator.MAX_BYTES) throw new DownloadFailure("TOO_LARGE");
                        try { out.write(bytes,0,count); } catch(IOException e) { throw new DownloadFailure("STORAGE_WRITE",e); }
                        if(System.currentTimeMillis()-lastProgress>500) {
                            if(!downloads.update(t)) throw new InterruptedIOException("CANCELLED");
                            lastProgress=System.currentTimeMillis();
                            getApplicationContext().getSystemService(NotificationManager.class).notify(notificationId(t),foreground(t).getNotification());
                        }
                    }
                    out.getFD().sync();
                }
                if(t.total>=0 && t.bytes!=t.total) throw new DownloadFailure("NETWORK");
                return;
            }
        }
        throw new DownloadFailure("REDIRECT_LIMIT");
    }

    private void publishFile(CatalogDownloads.Task t,File staging) throws IOException {
        String ext=BookFileValidator.extension(t.format);
        String title=t.title.replaceAll("[^\\p{L}\\p{N} ._()-]","_").trim();
        if(title.isEmpty()) title="book";
        if(title.length()>70) title=title.substring(0,70);
        String name=title+"-"+t.id.substring(0,8)+"-"+t.generation.substring(0,8)+"."+ext;
        if(t.tree.isEmpty()) {
            File folder=new File(getApplicationContext().getFilesDir(),"catalog-books");
            if(!folder.isDirectory()&&!folder.mkdirs()) throw new DownloadFailure("NO_SPACE");
            if(folder.getUsableSpace()<staging.length()+1024*1024) throw new DownloadFailure("NO_SPACE");
            File target=new File(folder,name);
            if(!target.createNewFile()) throw new DownloadFailure("FILE_EXISTS");
            t.pendingPath=target.getAbsolutePath();
            if(!downloads.update(t)) throw new InterruptedIOException("CANCELLED");
            try(FileOutputStream out=new FileOutputStream(target)) {
                copy(t,staging,out); out.getFD().sync();
            } catch(IOException e) { if(e instanceof InterruptedIOException) throw e; throw new DownloadFailure("STORAGE_WRITE",e); }
        } else {
            Uri tree=Uri.parse(t.tree);
            Uri parent=DocumentsContract.buildDocumentUriUsingTree(tree,DocumentsContract.getTreeDocumentId(tree));
            Uri document;
            try { document=DocumentsContract.createDocument(getApplicationContext().getContentResolver(),parent,mimeFor(ext),name); }
            catch(SecurityException e) { throw e; }
            catch(Exception e) { throw new DownloadFailure("STORAGE_WRITE",e); }
            if(document==null) throw new DownloadFailure("STORAGE_WRITE");
            t.pendingUri=document.toString();
            if(!downloads.update(t)) throw new InterruptedIOException("CANCELLED");
            try(OutputStream out=getApplicationContext().getContentResolver().openOutputStream(document,"w")) {
                if(out==null) throw new DownloadFailure("STORAGE_WRITE");
                copy(t,staging,out); out.flush();
            } catch(SecurityException e) { throw e; }
            catch(IOException e) { if(e instanceof InterruptedIOException) throw e; throw new DownloadFailure("STORAGE_WRITE",e); }
        }
    }
    private void copy(CatalogDownloads.Task t,File input,OutputStream out) throws IOException {
        try(InputStream in=new BufferedInputStream(new FileInputStream(input))) {
            byte[] buf=new byte[64*1024]; int n;
            while((n=in.read(buf))!=-1) { checkActive(t); out.write(buf,0,n); }
        }
    }
    private void checkActive(CatalogDownloads.Task t) throws InterruptedIOException {
        if(isStopped()||Thread.currentThread().isInterrupted()||!downloads.isCurrent(t)) throw new InterruptedIOException("CANCELLED");
    }
    private void cleanupPending(CatalogDownloads.Task t) {
        if(!t.pendingPath.isEmpty()) {
            File file=new File(t.pendingPath);
            File folder=new File(getApplicationContext().getFilesDir(),"catalog-books");
            if(folder.equals(file.getParentFile())) file.delete();
            t.pendingPath="";
        }
        if(!t.pendingUri.isEmpty()) {
            try { org.json.JSONArray garbage=new org.json.JSONArray(t.cleanupUris); garbage.put(t.pendingUri); t.cleanupUris=garbage.toString(); }
            catch(org.json.JSONException ignored) { t.cleanupUris=new org.json.JSONArray().put(t.pendingUri).toString(); }
            t.pendingUri="";
        }
        org.json.JSONArray remaining=new org.json.JSONArray();
        try {
            org.json.JSONArray garbage=new org.json.JSONArray(t.cleanupUris);
            for(int i=0;i<garbage.length();i++) {
                String owned=garbage.getString(i);
                try { if(!DocumentsContract.deleteDocument(getApplicationContext().getContentResolver(),Uri.parse(owned))) remaining.put(owned); }
                catch(java.io.FileNotFoundException removed) { /* Already removed by a stopped attempt. */ }
                catch(Exception denied) { remaining.put(owned); }
            }
        } catch(org.json.JSONException ignored) { /* Earlier versions stored no cleanup list. */ }
        t.cleanupUris=remaining.toString();
        downloads.update(t);
    }
    static void registerBook(Context context,CatalogDownloads.Task t) {
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                CatalogDownloads downloads=CatalogDownloads.get(context);
                CatalogDownloads.Task current=downloads.task(t.id);
                if(current==null || !current.generation.equals(t.generation) || !CatalogDownloads.COMPLETED.equals(current.status) || current.indexed) return;
                String path=t.localPath.isEmpty()?t.localUri:t.localPath;
                String folder=t.tree.isEmpty()?new File(context.getFilesDir(),"catalog-books").getAbsolutePath():t.tree;
                if(!JsonDB.contains(BookCSS.get().searchPathsJson,folder)) {
                    BookCSS.get().searchPathsJson=JsonDB.add(BookCSS.get().searchPathsJson,folder);
                    AppProfile.save(context);
                }
                FileMeta meta=AppDB.get().getOrCreate(path);
                meta.setTitle(t.title); meta.setAuthor(t.author);
                // Keep compound FB2.ZIP for providers with opaque URIs: the reader's stable
                // SAF copy must retain the archive extension, rather than masquerade as XML.
                meta.setExt(BookFileValidator.extension(t.format));
                if(!t.localPath.isEmpty()) meta.setPathTxt(new File(t.localPath).getName());
                meta.setSize(t.bytes); meta.setDate(System.currentTimeMillis()); meta.setIsSearchBook(true);
                AppDB.get().update(meta); TempHolder.listHash++;
                downloads.indexed(t.id,t.generation);
                EventBus.getDefault().post(new NotifyAllFragments());
            } catch(Exception ignored) { /* The verified file remains available from the queue. */ }
        });
    }
    @Override public void onStopped() {
        Call pending=call; if(pending!=null) pending.cancel();
        CatalogDownloads.Task t=running;
        if(t!=null && downloads!=null) synchronized(downloads) {
            CatalogDownloads.Task copy=downloads.task(t.id);
            if(copy!=null && copy.generation.equals(t.generation) && CatalogDownloads.active(copy.status)) {
                copy.status=CatalogDownloads.WAITING_NETWORK; downloads.update(copy);
            }
        }
        super.onStopped();
    }
    private ForegroundInfo foreground(CatalogDownloads.Task t) {
        Context c=getApplicationContext(); NotificationManager manager=c.getSystemService(NotificationManager.class);
        if(Build.VERSION.SDK_INT>=26) manager.createNotificationChannel(new NotificationChannel(CHANNEL,c.getString(R.string.downloads),NotificationManager.IMPORTANCE_LOW));
        PendingIntent open=PendingIntent.getActivity(c,0,new Intent(c,MainTabs2.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder builder=new NotificationCompat.Builder(c,CHANNEL)
                .setSmallIcon(R.drawable.ic_notification_librera).setContentTitle(t.title)
                .setContentText(c.getString(R.string.download)+" · "+t.format).setContentIntent(open)
                .setOnlyAlertOnce(true).setOngoing(true)
                .setProgress(100,t.total>0?(int)Math.min(100,t.bytes*100/t.total):0,t.total<=0)
                .addAction(android.R.drawable.ic_menu_close_clear_cancel,c.getString(R.string.cancel),WorkManager.getInstance(c).createCancelPendingIntent(getId()));
        return Build.VERSION.SDK_INT>=29?new ForegroundInfo(notificationId(t),builder.build(),ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC):new ForegroundInfo(notificationId(t),builder.build());
    }
    private static int notificationId(CatalogDownloads.Task t) { return 700000+(t.id.hashCode()&0x0fffffff); }
    private static String mimeFor(String ext) {
        switch(ext) {
            case "epub":return "application/epub+zip";
            case "fb2":return "application/x-fictionbook+xml";
            case "fb2.zip":return "application/zip";
            case "pdf":return "application/pdf";
            case "djvu":return "image/vnd.djvu";
            default:return "application/x-mobipocket-ebook";
        }
    }
    private static final class DownloadFailure extends IOException {
        DownloadFailure(String code) { super(code); }
        DownloadFailure(String code,Throwable cause) { super(code,cause); }
    }
}
