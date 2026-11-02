package com.foobnix.catalog;

import java.io.Serializable;

/** One file actually advertised by the source, not an invented format URL. */
public final class CatalogAcquisition implements Serializable {
    private static final long serialVersionUID = 1L;
    public String url = "";
    public String mimeType = "";
    public String format = "";
}
