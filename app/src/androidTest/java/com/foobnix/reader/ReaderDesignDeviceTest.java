package com.foobnix.reader;

import static org.junit.Assert.*;

import android.app.Instrumentation;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.TextView;
import android.widget.LinearLayout;

import androidx.core.graphics.ColorUtils;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.foobnix.model.AppProfile;
import com.foobnix.model.AppState;
import com.foobnix.pdf.info.R;
import com.foobnix.pdf.info.view.CustomSeek;
import com.foobnix.pdf.info.view.ReaderChrome;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.atomic.AtomicInteger;

/** Native layouts only; no user book, reading history or system setting is changed. */
@RunWith(AndroidJUnit4.class)
public class ReaderDesignDeviceTest {
    private Instrumentation instrumentation;
    private Context context;
    private int oldTheme;
    private boolean oldDay;

    @Before public void before() {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        context = instrumentation.getTargetContext(); AppProfile.init(context);
        oldTheme = AppState.get().appTheme; oldDay = AppState.get().isDayNotInvert;
    }

    @After public void after() {
        AppState.get().appTheme = oldTheme; AppState.get().isDayNotInvert = oldDay;
    }

    @Test public void phoneToolsPreserveActionsInBothOrientationsAndLargeText() {
        instrumentation.runOnMainSync(() -> {
            for (boolean paged : new boolean[]{false, true}) {
                checkReader(paged, 384, 832, 1.15f, false);
                checkReader(paged, 832, 384, 1.15f, false);
                checkReader(paged, 384, 832, 2f, true);
            }
        });
    }

    @Test public void tabletToolsPreserveActionsInBothOrientations() {
        instrumentation.runOnMainSync(() -> {
            for (boolean paged : new boolean[]{false, true}) {
                checkReader(paged, 1280, 800, 1f, false);
                checkReader(paged, 800, 1280, 2f, true);
            }
        });
    }

    @Test public void largeTextSettingsKeepSlidersAndCloseReachable() {
        instrumentation.runOnMainSync(() -> {
            Context themed = themed(2f, true);
            View popup = LayoutInflater.from(themed).inflate(R.layout.drag_popup, null, false);
            ViewGroup content = popup.findViewById(R.id.popupContent);
            content.addView(LayoutInflater.from(themed).inflate(R.layout.dialog_reading_pref, content, false));
            ((TextView) popup.findViewById(R.id.dialogTitle)).setText("Параметры чтения");
            popup.findViewById(R.id.onIconAction).setVisibility(View.GONE);
            AtomicInteger clicks = new AtomicInteger();
            popup.findViewById(R.id.closePopup).setOnClickListener(v -> clicks.incrementAndGet());
            ReaderChrome.popup(popup);
            ReaderChrome.popup(popup); // Reopening/reloading must not reparent a control twice.
            measure(popup, 360, 700);
            View close = popup.findViewById(R.id.closePopup);
            assertTarget(close); close.performClick(); assertEquals(1, clicks.get());
            CustomSeek size = popup.findViewById(R.id.fontWeight);
            if (size == null) size = popup.findViewById(R.id.lineHeight);
            assertNotNull(size);
            size.setVisibility(View.VISIBLE); measure(popup, 360, 700);
            View label = size.findViewById(R.id.name), seek = size.findViewById(R.id.seek);
            assertNotSame("Labels have their own line at large text", label.getParent(), seek.getParent());
            assertTarget(size.findViewById(R.id.plus)); assertTarget(size.findViewById(R.id.minus));
            assertTrue("The actual seekbar retains useful width", seek.getWidth() >= dp(seek, 80));
            assertNotNull(popup.findViewById(R.id.left)); assertNotNull(popup.findViewById(R.id.rigth));
            assertPreviewColorsSurviveChrome(themed);
        });
    }

    private static void assertPreviewColorsSurviveChrome(Context themed) {
        View popup = LayoutInflater.from(themed).inflate(R.layout.drag_popup, null, false);
        LinearLayout previews = new LinearLayout(themed);
        ((ViewGroup) popup.findViewById(R.id.popupContent)).addView(previews);
        TextView day = new TextView(themed), night = new TextView(themed);
        day.setId(R.id.onDayColor); night.setId(R.id.onNigthColor);
        day.setTextColor(Color.BLACK); night.setTextColor(Color.WHITE);
        day.setOnClickListener(v -> { }); night.setOnClickListener(v -> { });
        int[] lightTexture = {0xffefe0c2, 0xffd4c5a2}, darkTexture = {0xff443728, 0xff66553c};
        Bitmap dayPixels = Bitmap.createBitmap(lightTexture, 2, 1, Bitmap.Config.ARGB_8888);
        Bitmap nightPixels = Bitmap.createBitmap(darkTexture, 2, 1, Bitmap.Config.ARGB_8888);
        BitmapDrawable dayBackground = new BitmapDrawable(themed.getResources(), dayPixels);
        BitmapDrawable nightBackground = new BitmapDrawable(themed.getResources(), nightPixels);
        day.setBackground(dayBackground); night.setBackground(nightBackground);
        previews.addView(day); previews.addView(night);
        ReaderChrome.popup(popup); ReaderChrome.popup(popup);
        assertEquals("Day texture keeps its contrasting label", Color.BLACK, day.getCurrentTextColor());
        assertEquals("Night texture keeps its contrasting label", Color.WHITE, night.getCurrentTextColor());
        for (int pixel : lightTexture) assertTrue(ColorUtils.calculateContrast(day.getCurrentTextColor(), pixel) >= 4.5);
        for (int pixel : darkTexture) assertTrue(ColorUtils.calculateContrast(night.getCurrentTextColor(), pixel) >= 4.5);
        assertSame(dayBackground, day.getBackground()); assertSame(nightBackground, night.getBackground());
        // A user-picked solid preset also keeps its own text colour, not forced black/white.
        int custom = 0xff23432b; day.setBackgroundColor(0xfff2e9ce); day.setTextColor(custom);
        ReaderChrome.popup(popup); assertEquals(custom, day.getCurrentTextColor());
        assertTrue(day.hasOnClickListeners()); assertTrue(night.hasOnClickListeners());
    }

    private void checkReader(boolean paged, int width, int height, float scale, boolean dark) {
        View root = LayoutInflater.from(themed(scale, dark)).inflate(
                paged ? R.layout.activity_horiziontal_view : R.layout.activity_vertical_view, null, false);
        int[] actions = {R.id.onLibrary, paged ? R.id.bookClose : R.id.close,
                paged ? R.id.bookPref : R.id.prefTop, R.id.onShowSearch, R.id.onRecent,
                R.id.editTop2, R.id.thumbnail, R.id.onBookmarks, R.id.onDocDontext,
                R.id.onModeChange, R.id.linkHistory, R.id.textToSpeach, R.id.bookMenu, R.id.lockUnlock};
        AtomicInteger clicks = new AtomicInteger();
        for (int id : actions) {
            View button = root.findViewById(id); assertNotNull("Existing reader action " + id, button);
            button.setVisibility(View.VISIBLE); button.setOnClickListener(v -> clicks.incrementAndGet());
        }
        ReaderChrome.apply(root); measure(root, width, height);
        ReaderChrome.apply(root); measure(root, width, height);
        for (int id : actions) {
            View button = root.findViewById(id); assertTarget(button);
            assertTrue("Action listener is preserved", button.performClick());
        }
        assertEquals(actions.length, clicks.get());
        checkStrip(root, paged ? R.id.readerAppearanceTools : R.id.readerPrimaryTools,
                paged ? R.id.bookPref : R.id.prefTop);
        checkStrip(root, R.id.readerNavigationTools, R.id.linkHistory);
    }

    private void checkStrip(View root, int stripId, int lastId) {
        HorizontalScrollView strip = root.findViewById(stripId);
        assertNotNull(strip); assertTrue("Toolbar has available width", strip.getWidth() > 0);
        View last = root.findViewById(lastId);
        Rect rect = new Rect(0, 0, last.getWidth(), last.getHeight());
        strip.offsetDescendantRectToMyCoords(last, rect);
        strip.scrollTo(Math.max(0, rect.right - strip.getWidth()), 0);
        assertTrue("Last action is reachable by horizontal scroll", rect.right - strip.getScrollX() <= strip.getWidth() + 1);
        assertTrue("Last action fits vertically", last.getHeight() <= strip.getHeight());
    }

    private Context themed(float scale, boolean dark) {
        AppState.get().appTheme = dark ? AppState.THEME_DARK : AppState.THEME_LIGHT;
        AppState.get().isDayNotInvert = !dark;
        Configuration config = new Configuration(context.getResources().getConfiguration());
        config.fontScale = scale;
        return new ContextThemeWrapper(context.createConfigurationContext(config),
                dark ? R.style.StyledIndicatorsBlack : R.style.StyledIndicatorsWhite);
    }

    private static void measure(View root, int width, int height) {
        int w = dp(root, width), h = dp(root, height);
        root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, w, h);
    }

    private static void assertTarget(View button) {
        assertTrue("Touch target width: " + button.getId(), button.getWidth() >= dp(button, 48));
        assertTrue("Touch target height: " + button.getId(), button.getHeight() >= dp(button, 48));
    }
    private static int dp(View view, int value) { return Math.round(value * view.getResources().getDisplayMetrics().density); }
}
