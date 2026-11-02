package com.foobnix.catalog;

import android.content.Context;

import androidx.annotation.NonNull;

import com.bumptech.glide.Glide;
import com.bumptech.glide.Priority;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.Options;
import com.bumptech.glide.load.data.DataFetcher;
import com.bumptech.glide.load.model.ModelLoader;
import com.bumptech.glide.load.model.ModelLoaderFactory;
import com.bumptech.glide.load.model.MultiModelLoaderFactory;
import com.bumptech.glide.signature.ObjectKey;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

/** Registers only the explicit CatalogCover model; other library images keep their own loader. */
public final class CatalogCoverLoader implements ModelLoader<CatalogCover, InputStream> {
    private static boolean registered;

    public static synchronized void register(Context context) {
        if (registered) return;
        Glide.get(context.getApplicationContext()).getRegistry()
                .append(CatalogCover.class, InputStream.class, new Factory());
        registered = true;
    }

    @Override public LoadData<InputStream> buildLoadData(@NonNull CatalogCover model, int width,
                                                       int height, @NonNull Options options) {
        return new LoadData<>(new ObjectKey("catalog-cover-v1:" + model.url), new Fetcher(model.url));
    }

    @Override public boolean handles(@NonNull CatalogCover model) { return true; }

    private static final class Factory implements ModelLoaderFactory<CatalogCover, InputStream> {
        @NonNull @Override public ModelLoader<CatalogCover, InputStream> build(@NonNull MultiModelLoaderFactory factory) {
            return new CatalogCoverLoader();
        }
        @Override public void teardown() { }
    }

    private static final class Fetcher implements DataFetcher<InputStream> {
        private final String url;
        private final CatalogCoverFetcher fetcher = new CatalogCoverFetcher();
        private InputStream stream;

        Fetcher(String url) { this.url = url; }

        @Override public void loadData(@NonNull Priority priority, @NonNull DataCallback<? super InputStream> callback) {
            // Glide invokes this on its source executor, never the UI thread.
            try {
                stream = new ByteArrayInputStream(fetcher.fetch(url));
                callback.onDataReady(stream);
            } catch (IOException failure) {
                callback.onLoadFailed(failure);
            }
        }

        @Override public void cleanup() {
            if (stream != null) {
                try { stream.close(); } catch (IOException ignored) { }
                stream = null;
            }
        }

        @Override public void cancel() { fetcher.cancel(); }
        @NonNull @Override public Class<InputStream> getDataClass() { return InputStream.class; }
        @NonNull @Override public DataSource getDataSource() { return DataSource.REMOTE; }
    }
}
