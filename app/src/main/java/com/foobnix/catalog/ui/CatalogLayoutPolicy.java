package com.foobnix.catalog.ui;

/** Uses the width available to this fragment, including split-screen, not the device diagonal. */
public final class CatalogLayoutPolicy {
    private CatalogLayoutPolicy() {}

    public static boolean isTwoPane(float widthDp, float fontScale) {
        return widthDp >= 840f * Math.max(1f, Math.min(1.4f, fontScale));
    }

    public static int detailWidth(float widthDp) {
        return Math.round(Math.max(360f, Math.min(440f, widthDp * .36f)));
    }

    public static int columns(float contentWidthDp, float fontScale) {
        float minimum = 156f * Math.max(1f, Math.min(1.5f, fontScale));
        return Math.max(1, Math.min(6, (int) (contentWidthDp / minimum)));
    }
}
