package com.foobnix.catalog;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.io.IOException;

/** At most five bounded OPDS feeds per home warmup; one for explicit next-page look-ahead. */
public final class CatalogPrefetchWorker extends Worker {
    private volatile CatalogRepository repository;

    public CatalogPrefetchWorker(@NonNull Context context, @NonNull WorkerParameters parameters) {
        super(context, parameters);
    }

    @NonNull @Override public Result doWork() {
        Context app = getApplicationContext();
        String mirror = getInputData().getString(CatalogWarmup.MIRROR);
        if (mirror == null) return Result.success();
        SharedPreferences prefs = app.getSharedPreferences("flibusta_catalog", Context.MODE_PRIVATE);
        if (!mayFetch(mirror)) return Result.success();
        String next = getInputData().getString(CatalogWarmup.NEXT);
        if (next == null) {
            if (CatalogWarmup.recentWarmup(prefs, mirror) || CatalogWarmup.recentAttempt(prefs, mirror)) return Result.success();
            prefs.edit().putLong("warmup_attempt_at", System.currentTimeMillis())
                    .putString("warmup_attempt_mirror", mirror).apply();
        }
        repository = new CatalogRepository(app);
        repository.setNetworkAllowed(() -> mayFetch(mirror));
        try {
            if (next != null) repository.loadFresh(next, false);
            else {
                CatalogPage home = repository.warmLanding();
                if (mayFetch(mirror) && CatalogRepository.hasBooks(home) && CatalogRepository.isFresh(home)) {
                    prefs.edit().putLong("warmup_at", System.currentTimeMillis()).putString("warmup_mirror", mirror).apply();
                }
            }
        } catch (IOException ignored) {
            // Speculation is optional: no error banner, notification, automatic
            // retry loop, or delay to opening the user's local library.
        } finally {
            repository.cancel();
            repository = null;
        }
        return Result.success();
    }

    private boolean mayFetch(String expectedMirror) {
        Context app = getApplicationContext();
        return !isStopped() && !CatalogWarmup.offline(app) && CatalogWarmup.unmetered(app)
                && expectedMirror.equals(app.getSharedPreferences("flibusta_catalog", Context.MODE_PRIVATE)
                    .getString("mirror", ""));
    }

    @Override public void onStopped() {
        CatalogRepository current = repository;
        if (current != null) current.cancel();
        super.onStopped();
    }
}
