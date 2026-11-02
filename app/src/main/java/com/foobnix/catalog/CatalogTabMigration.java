package com.foobnix.catalog;

/** Adds the new tab once without changing a reader's existing order or hidden tabs. */
public final class CatalogTabMigration {
    private CatalogTabMigration() { }

    public static String migrate(String order, String defaults) {
        if (order == null || order.trim().isEmpty()) {
            return defaults;
        }
        for (String part : order.split(",")) {
            if ("8#0".equals(part) || "8#1".equals(part)) {
                return order;
            }
        }
        return order + (order.endsWith(",") ? "" : ",") + "8#1";
    }
}
