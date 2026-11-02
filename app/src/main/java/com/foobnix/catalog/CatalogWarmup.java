package com.foobnix.catalog;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import androidx.work.Constraints;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Best-effort metadata preparation. Never fetches book files or starts foreground work. */
public final class CatalogWarmup {
    static final String HOME_WORK = "catalog-metadata-home";
    static final String NEXT_WORK = "catalog-metadata-next";
    static final String MIRROR = "mirror";
    static final String NEXT = "next";
    static final long WARM_INTERVAL_MS = TimeUnit.HOURS.toMillis(6);
    private static final long FAILED_ATTEMPT_INTERVAL_MS = TimeUnit.MINUTES.toMillis(15);
    private static final AtomicBoolean HYDRATION_SCHEDULED = new AtomicBoolean();
    private static final ExecutorService DISK = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "catalog-cache-hydration");
        thread.setDaemon(true);
        return thread;
    });

    private CatalogWarmup() { }

    /** Call from application UI startup; WorkManager waits for an unmetered connection. */
    public static void schedule(Context context) {
        Context app = context.getApplicationContext();
        hydrateSavedHome(app);
        if (offline(app)) { cancel(app); return; }
        SharedPreferences prefs = app.getSharedPreferences("flibusta_catalog", Context.MODE_PRIVATE);
        String mirror = prefs.getString("mirror", "");
        if (recentWarmup(prefs, mirror) || recentAttempt(prefs, mirror)) return;
        enqueue(app, HOME_WORK, new Data.Builder().putString(MIRROR, mirror).build());
    }

    /** Prepare parsed objects after process restart, even offline or on a metered network. */
    private static void hydrateSavedHome(Context app) {
        if (!HYDRATION_SCHEDULED.compareAndSet(false, true)) return;
        DISK.execute(() -> {
            CatalogRepository repository = new CatalogRepository(app);
            repository.setNetworkAllowed(() -> false);
            try { repository.cachedLanding(); }
            catch (IOException missing) { /* A first launch has no saved metadata. */ }
        });
    }

    /** A single look-ahead page, deduplicated across repeated binds and rotations. */
    public static void prefetchNext(Context context, CatalogPage page) {
        if (page == null || page.nextUrl == null || page.nextUrl.isEmpty() || offline(context)) return;
        try {
            if (!CatalogUrls.isCatalogUrl(page.nextUrl)
                    || CatalogUrls.cacheKey(page.url).equals(CatalogUrls.cacheKey(page.nextUrl))) return;
        } catch (IOException invalid) { return; }
        Context app = context.getApplicationContext();
        String mirror = app.getSharedPreferences("flibusta_catalog", Context.MODE_PRIVATE).getString("mirror", "");
        enqueue(app, NEXT_WORK, new Data.Builder().putString(MIRROR, mirror).putString(NEXT, page.nextUrl).build());
    }

    public static void cancel(Context context) {
        WorkManager work = WorkManager.getInstance(context.getApplicationContext());
        work.cancelUniqueWork(HOME_WORK);
        work.cancelUniqueWork(NEXT_WORK);
    }

    private static void enqueue(Context app, String name, Data data) {
        WorkManager.getInstance(app).enqueueUniqueWork(name, ExistingWorkPolicy.KEEP, request(data));
    }

    static OneTimeWorkRequest request(Data data) {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.UNMETERED).setRequiresBatteryNotLow(true).build();
        return new OneTimeWorkRequest.Builder(CatalogPrefetchWorker.class)
                .setConstraints(constraints).setInputData(data).setInitialDelay(2, TimeUnit.SECONDS).build();
    }

    static boolean offline(Context context) {
        return context.getSharedPreferences("catalog-ui", Context.MODE_PRIVATE).getBoolean("offline", false);
    }

    static boolean recentWarmup(SharedPreferences prefs, String mirror) {
        long age = System.currentTimeMillis() - prefs.getLong("warmup_at", 0);
        return age >= 0 && age < WARM_INTERVAL_MS && mirror.equals(prefs.getString("warmup_mirror", ""));
    }

    static boolean recentAttempt(SharedPreferences prefs, String mirror) {
        long age = System.currentTimeMillis() - prefs.getLong("warmup_attempt_at", 0);
        return age >= 0 && age < FAILED_ATTEMPT_INTERVAL_MS
                && mirror.equals(prefs.getString("warmup_attempt_mirror", ""));
    }

    static boolean unmetered(Context context) {
        ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (manager == null || manager.isActiveNetworkMetered()) return false;
        Network active = manager.getActiveNetwork();
        NetworkCapabilities capabilities = active == null ? null : manager.getNetworkCapabilities(active);
        return capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }
}
