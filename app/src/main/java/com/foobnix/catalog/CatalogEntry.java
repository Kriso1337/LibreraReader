package com.foobnix.catalog;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/** Both OPDS navigation entries and downloadable books use this model. */
public final class CatalogEntry implements Serializable {
    private static final long serialVersionUID = 1L;
    public String id = "";
    public String title = "";
    public String author = "";
    public String summary = "";
    public String coverUrl = "";
    public String navigationUrl = "";
    public List<CatalogAcquisition> acquisitions = new ArrayList<>();
}
