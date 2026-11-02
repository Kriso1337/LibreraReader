package com.foobnix.catalog;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.Uri;
import androidx.work.BackoffPolicy;
import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;
import com.foobnix.pdf.info.R;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Persistent per-book-and-format jobs. Calls that touch this store belong off the UI thread. */
public final class CatalogDownloads {
    public static final String QUEUED="queued", RUNNING="running", WAITING_NETWORK="waiting_network",
            COMPLETED="completed", FAILED="failed", CANCELLED="cancelled";
    static final String TAG="catalog-download", KEY_ID="task_id", KEY_GENERATION="generation";
    private static volatile CatalogDownloads instance;
    final Context context;
    final CatalogDownloadStore store;
    private final Map<String,Long> existenceChecks=new HashMap<>();

    public static final class Task {
        public String id="",bookId="",title="",author="",format="",status="",error="",errorCode="",localPath="",localUri="";
        public long bytes=0,total=-1;
        String url="",mime="",tree="",generation="",workId="",pendingUri="",pendingPath="",cleanupUris="[]";
        long created;
        boolean indexed;
    }
    private CatalogDownloads(Context context) {
        this.context=context.getApplicationContext(); store=new CatalogDownloadStore(this.context);
    }
    public static CatalogDownloads get(Context context) {
        if(instance==null) synchronized(CatalogDownloads.class) {
            if(instance==null) instance=new CatalogDownloads(context);
        }
        return instance;
    }

    public List<Task> getTasks() {
        List<Task> list;
        synchronized(this) { list=store.all(); }
        // The notification's WorkManager cancel action is reflected in the persisted UI state.
        Map<String, WorkInfo> infos=new HashMap<>();
        boolean snapshotLoaded=false;
        try {
            for(WorkInfo info:WorkManager.getInstance(context).getWorkInfosByTag(TAG).get(2,TimeUnit.SECONDS)) infos.put(info.getId().toString(),info);
            snapshotLoaded=true;
        } catch(Exception ignored) { /* A busy WorkManager must not hide the persistent queue. */ }
        boolean network=hasNetwork();
        for(int index=0;index<list.size();index++) {
            Task t=list.get(index);
            WorkInfo info=infos.get(t.workId);
            if(info!=null && info.getState()==WorkInfo.State.CANCELLED && active(t.status)) {
                t.status=CANCELLED;
                if(!update(t)) { Task latest=task(t.id); if(latest!=null)t=latest; }
                info=infos.get(t.workId);
            }
            if(info!=null && info.getState().isFinished() && active(t.status)) {
                t.status=FAILED; setError(t,"INTERRUPTED");
                if(!update(t)) { Task latest=task(t.id); if(latest!=null)t=latest; }
                info=infos.get(t.workId);
            }
            if(snapshotLoaded && info==null && active(t.status) && System.currentTimeMillis()-t.created>30_000) {
                t.status=FAILED; setError(t,"SCHEDULER");
                if(!update(t)) { Task latest=task(t.id); if(latest!=null)t=latest; }
            }
            if(!network && active(t.status)) t.status=WAITING_NETWORK;
            if(COMPLETED.equals(t.status)) {
                boolean check;
                synchronized(this) {
                    long now=System.currentTimeMillis();
                    check=now-existenceChecks.getOrDefault(t.id,0L)>30_000;
                    if(check) existenceChecks.put(t.id,now);
                }
                if(check && !exists(t)) synchronized(this) {
                    Task current=store.get(t.id);
                    if(current!=null && current.generation.equals(t.generation) && COMPLETED.equals(current.status)) {
                        t.status=FAILED; setError(t,"FILE_NOT_FOUND"); store.put(t);
                    }
                }
                if(COMPLETED.equals(t.status) && !t.indexed) CatalogDownloadWorker.registerBook(context,t);
            }
            list.set(index,t);
        }
        return list;
    }
    public synchronized Task findCompleted(String bookId) {
        for(Task t:store.all()) if(t.bookId.equals(bookId) && COMPLETED.equals(t.status)) {
            if(exists(t)) return t;
            t.status=FAILED; setError(t,"FILE_NOT_FOUND"); store.put(t);
        }
        return null;
    }
    public synchronized String enqueue(CatalogEntry entry, CatalogAcquisition acquisition, Uri treeOrNull) {
        if(entry==null || acquisition==null || entry.id==null || entry.id.isEmpty()) throw new IllegalArgumentException("Missing book");
        String format=BookFileValidator.extension(acquisition.format).toUpperCase(java.util.Locale.ROOT);
        CatalogDownloadWorker.checkedUrl(acquisition.url);
        if(treeOrNull!=null && (!"content".equals(treeOrNull.getScheme()) || !android.provider.DocumentsContract.isTreeUri(treeOrNull))) {
            throw new IllegalArgumentException("Expected a document tree");
        }
        String id=key(entry.id,format);
        Task existing=store.get(id);
        if(existing!=null && (active(existing.status) || (COMPLETED.equals(existing.status) && exists(existing)))) return id;
        Task t=existing==null?new Task():existing; t.id=id; t.bookId=entry.id; t.title=entry.title==null?"":entry.title;
        t.author=entry.author==null?"":entry.author; t.format=format; t.url=acquisition.url;
        t.mime=acquisition.mimeType==null?"application/octet-stream":acquisition.mimeType;
        t.tree=treeOrNull==null?"":treeOrNull.toString(); schedule(t); return id;
    }
    public synchronized void cancel(String id) {
        Task t=store.get(id);
        if(t==null || !active(t.status)) return;
        t.status=CANCELLED; store.put(t);
        WorkManager.getInstance(context).cancelUniqueWork(workName(id));
    }
    public synchronized void retry(String id) {
        Task t=store.get(id);
        if(t==null || active(t.status) || COMPLETED.equals(t.status)) return;
        schedule(t);
    }
    public synchronized void retry(String id,Uri treeOrNull) {
        Task t=store.get(id);
        if(t==null || active(t.status) || COMPLETED.equals(t.status)) return;
        if(treeOrNull!=null && (!"content".equals(treeOrNull.getScheme()) || !android.provider.DocumentsContract.isTreeUri(treeOrNull))) throw new IllegalArgumentException("Expected document tree");
        t.tree=treeOrNull==null?"":treeOrNull.toString(); schedule(t);
    }
    private void schedule(Task t) {
        if(!t.generation.isEmpty()) {
            File abandoned=new File(new File(context.getCacheDir(),"catalog-downloads"),t.id+"-"+t.generation+".part");
            if(abandoned.isFile()) abandoned.delete();
        }
        t.generation=UUID.randomUUID().toString(); t.status=QUEUED; t.error=""; t.errorCode="";
        t.bytes=0; t.total=-1; t.localPath=""; t.localUri=""; t.indexed=false; t.created=System.currentTimeMillis();
        // Preserve any owned unfinished document across retries, including revoked SAF access.
        if(!t.pendingUri.isEmpty()) {
            try { org.json.JSONArray garbage=new org.json.JSONArray(t.cleanupUris); garbage.put(t.pendingUri); t.cleanupUris=garbage.toString(); }
            catch(org.json.JSONException ignored) { t.cleanupUris=new org.json.JSONArray().put(t.pendingUri).toString(); }
            t.pendingUri="";
        }
        OneTimeWorkRequest request=new OneTimeWorkRequest.Builder(CatalogDownloadWorker.class)
                .setInputData(new Data.Builder().putString(KEY_ID,t.id).putString(KEY_GENERATION,t.generation).build())
                .setConstraints(new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).addTag(TAG).build();
        t.workId=request.getId().toString(); store.put(t);
        try {
            WorkManager.getInstance(context).enqueueUniqueWork(workName(t.id),ExistingWorkPolicy.REPLACE,request)
                    .getResult().get(10,TimeUnit.SECONDS);
        } catch(Exception e) {
            WorkManager.getInstance(context).cancelUniqueWork(workName(t.id));
            t.status=FAILED; setError(t,"SCHEDULER"); store.put(t);
            if(e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException("Cannot schedule download",e);
        }
    }
    synchronized Task task(String id) { return store.get(id); }
    synchronized void indexed(String id,String generation) {
        Task current=store.get(id);
        if(current!=null && current.generation.equals(generation) && COMPLETED.equals(current.status)) {
            current.indexed=true; store.put(current);
        }
    }
    synchronized boolean update(Task t) {
        Task current=store.get(t.id);
        if(current==null || !current.generation.equals(t.generation) ||
                (COMPLETED.equals(current.status) && !COMPLETED.equals(t.status)) ||
                (CANCELLED.equals(current.status) && !CANCELLED.equals(t.status))) return false;
        store.put(t); return true;
    }
    synchronized boolean isCurrent(Task t) {
        Task current=store.get(t.id);
        return current!=null && current.generation.equals(t.generation) && active(current.status);
    }
    static boolean active(String status) { return QUEUED.equals(status)||RUNNING.equals(status)||WAITING_NETWORK.equals(status); }
    static String workName(String id) { return TAG+"-"+id; }
    void setError(Task t,String code) {
        t.errorCode=code;
        int res="STORAGE_ACCESS".equals(code)?R.string.folder_no_access:"FILE_NOT_FOUND".equals(code)?R.string.file_not_found:R.string.can_t_download;
        t.error=context.getString(res);
    }
    private boolean exists(Task t) {
        if(!t.localPath.isEmpty()) return new File(t.localPath).isFile() && new File(t.localPath).length()>0;
        if(!t.localUri.isEmpty()) try(AssetFileDescriptor fd=context.getContentResolver().openAssetFileDescriptor(Uri.parse(t.localUri),"r")) { return fd!=null; }
        catch(Exception e) { return false; }
        return false;
    }
    private boolean hasNetwork() {
        ConnectivityManager cm=(ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);
        try {
            NetworkCapabilities caps=cm==null?null:cm.getNetworkCapabilities(cm.getActiveNetwork());
            return caps!=null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } catch(SecurityException e) { return true; }
    }
    static String key(String bookId,String format) {
        try {
            byte[] digest=MessageDigest.getInstance("SHA-256").digest((bookId+"\u0000"+format).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex=new StringBuilder(); for(byte b:digest)hex.append(String.format(java.util.Locale.ROOT,"%02x",b&255)); return hex.toString();
        } catch(Exception e) { throw new IllegalStateException(e); }
    }
}
