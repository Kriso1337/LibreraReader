package com.foobnix.catalog.ui;

import org.junit.Test;
import static org.junit.Assert.*;

public class CatalogLayoutPolicyTest {
    @Test public void tabletLandscapeLeavesRoomForBothPanes() {
        for (int width : new int[]{960, 1024, 1280}) {
            assertTrue(CatalogLayoutPolicy.isTwoPane(width, 1f));
            int detail = CatalogLayoutPolicy.detailWidth(width);
            assertTrue(detail >= 360 && detail <= 440);
            assertTrue(width - detail >= 360);
        }
    }

    @Test public void portraitAndSplitWindowUseOnePane() {
        assertFalse(CatalogLayoutPolicy.isTwoPane(800, 1f));
        assertFalse(CatalogLayoutPolicy.isTwoPane(600, 1f));
        assertFalse(CatalogLayoutPolicy.isTwoPane(320, 1f));
        assertEquals(1, CatalogLayoutPolicy.columns(288, 1f));
    }

    @Test public void phoneLandscapeReservesHeightForTheShelf() {
        assertFalse(CatalogLayoutPolicy.isTwoPane(900, 280, 1f));
        assertTrue(CatalogLayoutPolicy.isTwoPane(1280, 680, 1f));
    }

    @Test public void compactLandscapeRowsLeaveRoomForTheBookTitle() {
        assertEquals(2, CatalogLayoutPolicy.compactColumns(740, 1.15f));
        assertEquals(1, CatalogLayoutPolicy.compactColumns(740, 2f));
        assertEquals(1, CatalogLayoutPolicy.compactColumns(500, 1.15f));
    }

    @Test public void normalPhoneTextKeepsTwoColumnsButAccessibilityTextReflows() {
        assertEquals(2, CatalogLayoutPolicy.columns(352, 1.15f));
        assertEquals(1, CatalogLayoutPolicy.columns(352, 2f));
    }

    @Test public void largeTextGetsFewerColumnsAndMoreReadingRoom() {
        assertFalse(CatalogLayoutPolicy.isTwoPane(960, 2f));
        assertTrue(CatalogLayoutPolicy.isTwoPane(1280, 2f));
        assertTrue(CatalogLayoutPolicy.columns(752, 2f)
                < CatalogLayoutPolicy.columns(752, 1f));
        assertEquals(1, CatalogLayoutPolicy.columns(240, 2f));
    }
}
