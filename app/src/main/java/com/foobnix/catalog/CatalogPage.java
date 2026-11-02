package com.foobnix.catalog;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

public final class CatalogPage implements Serializable {
    private static final long serialVersionUID = 1L;
    public String title = "";
    public String url = "";
    public String nextUrl = "";
    public String searchTemplate = "";
    public String searchDescriptionUrl = "";
    public List<CatalogEntry> entries = new ArrayList<>();
    public boolean fromCache;
    public long fetchedAt;
}
