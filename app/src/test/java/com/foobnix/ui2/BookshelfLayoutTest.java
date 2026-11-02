package com.foobnix.ui2;

import org.junit.Test;

import static org.junit.Assert.*;

public class BookshelfLayoutTest {
    @Test public void standardPhoneTextFitsThreeColumns() {
        assertEquals(3, BookshelfLayout.columns(352f, 1.15f));
        assertEquals(3, BookshelfLayout.columns(328f, 1f));
    }

    @Test public void narrowWindowsAndLargeTextKeepReadableColumns() {
        assertEquals(2, BookshelfLayout.columns(288f, 1f));
        assertEquals(1, BookshelfLayout.columns(352f, 2f));
        assertEquals(1, BookshelfLayout.columns(72f, 1f));
        assertEquals(8, BookshelfLayout.columns(2000f, 1f));
    }

    @Test public void phoneBooksLeaveRoomForThreeCompleteRows() {
        for (float height : new float[]{480f, 510f, 600f}) {
            int cover = BookshelfLayout.coverHeight(112f, height, 1.15f, true);
            assertTrue((cover + 62f * 1.15f + 24f) * 3 <= height + 2);
        }
    }

    @Test public void catalogBooksUseTheSpaceFreedByFewerMetadataRows() {
        assertTrue(BookshelfLayout.coverHeight(112f, 510f, 1.15f, false)
                > BookshelfLayout.coverHeight(112f, 510f, 1.15f, true));
    }

    @Test public void accessibilityTextGetsMoreRoomInsteadOfSmallerText() {
        assertTrue(BookshelfLayout.coverHeight(352f, 510f, 2f, true)
                > BookshelfLayout.coverHeight(112f, 510f, 1.15f, true));
    }

    @Test public void shortAndUnmeasuredWindowsKeepCoversVisible() {
        assertEquals(56, BookshelfLayout.coverHeight(100f, 90f, 1f, true));
        assertTrue(BookshelfLayout.coverHeight(100f, 0f, 1f, false) >= 56);
    }
}
