package com.foobnix.ui2;

public final class BookshelfLayout {
    private BookshelfLayout() {}

    public static int columns(float widthDp, float fontScale) {
        float minimum = 100f + Math.max(0f, fontScale - 1.2f) * 180f;
        return Math.max(1, Math.min(8, (int) (widthDp / minimum)));
    }

    public static int coverHeight(float cellWidthDp, float viewportHeightDp, float fontScale, boolean metadata) {
        float scale = Math.max(1f, fontScale);
        int rows = scale <= 1.3f ? 3 : scale <= 1.7f ? 2 : 1;
        float text = (metadata ? 62f : 48f) * scale + 24f;
        float available = (viewportHeightDp > 0 ? viewportHeightDp : 540f) / rows - text;
        float natural = Math.min(132f, Math.max(40f, cellWidthDp - 12f)) * 1.4f;
        return Math.round(Math.max(56f, Math.min(natural, available)));
    }
}
