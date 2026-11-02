package com.foobnix.catalog;

/** A distinct Glide model keeps catalog covers out of the legacy authenticated OPDS loader. */
public final class CatalogCover {
    public final String url;

    public CatalogCover(String url) {
        this.url = url == null ? "" : url;
    }

    @Override public boolean equals(Object other) {
        return other instanceof CatalogCover && url.equals(((CatalogCover) other).url);
    }

    @Override public int hashCode() { return url.hashCode(); }
}
