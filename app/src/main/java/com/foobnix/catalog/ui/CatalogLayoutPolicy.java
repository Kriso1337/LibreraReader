package com.foobnix.catalog.ui;

/** Uses the width available to this fragment, including split-screen, not the device diagonal. */
public final class CatalogLayoutPolicy {
    private CatalogLayoutPolicy() {}

    public static boolean isTwoPane(float widthDp, float fontScale) {
        return widthDp >= 840f * Math.max(1f, Math.min(1.4f, fontScale));
    }

    public static boolean isTwoPane(float widthDp, float heightDp, float fontScale) {
        return heightDp >= 480 && isTwoPane(widthDp, fontScale);
    }

    public static int detailWidth(float widthDp) {
        return Math.round(Math.max(360f, Math.min(440f, widthDp * .36f)));
    }

    public static int compactColumns(float contentWidthDp, float fontScale) {
        // Leave 60dp for the jacket plus a readable text column; at large text the
        // phone uses the whole width for each book.
        float minimum = 280f * Math.max(1f, Math.min(2f, fontScale));
        return Math.max(1, Math.min(2, (int) (contentWidthDp / minimum)));
    }

    public static int columns(float contentWidthDp, float fontScale) {
        // Moderate system sizes (for example Samsung's 115%) still fit a phone's
        // two useful book columns. At 200% the cell grows to 234dp and reflows.
        float minimum = 156f * (1f + (Math.max(1f, Math.min(2f, fontScale)) - 1f) * .5f);
        return Math.max(1, Math.min(6, (int) (contentWidthDp / minimum)));
    }
}
