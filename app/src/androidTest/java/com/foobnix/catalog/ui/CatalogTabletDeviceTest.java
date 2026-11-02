package com.foobnix.catalog.ui;

import static org.junit.Assert.*;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.fragment.app.Fragment;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.foobnix.catalog.CatalogAcquisition;
import com.foobnix.catalog.CatalogEntry;
import com.foobnix.catalog.CatalogPage;
import com.foobnix.catalog.CatalogRepository;
import com.foobnix.model.AppProfile;
import com.foobnix.model.AppState;
import com.foobnix.pdf.info.R;
import com.foobnix.ui2.MainTabs2;
import com.foobnix.ui2.fragment.CatalogFragment;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Real Views in the real MainTabs2 host, with deterministic metadata injected only from tests.
 * Run on a tablet AVD (for example 1280x800 dp). No live catalog or ebook download is needed.
 * Screenshots: target application's externalFilesDir/testscreens/*.png.
 */
@RunWith(AndroidJUnit4.class)
public class CatalogTabletDeviceTest {
    private Instrumentation instrumentation;
    private Context context;
    private ActivityScenario<MainTabs2> host;
    private String oldOrder, oldLanguage;
    private int oldTheme;
    private boolean oldDay, oldSystemTheme, oldOffline;
    private float oldFontScale;
    private int oldAutoRotation, oldRotation;

    @Before public void setUp() {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        context = instrumentation.getTargetContext();
        AppProfile.init(context);
        AppState state = AppState.get();
        oldOrder = state.tabsOrder9; oldTheme = state.appTheme;
        oldLanguage = state.appLang;
        oldDay = state.isDayNotInvert; oldSystemTheme = state.isSystemThemeColor;
        oldFontScale = Settings.System.getFloat(context.getContentResolver(), Settings.System.FONT_SCALE, 1f);
        oldAutoRotation = Settings.System.getInt(context.getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, 1);
        oldRotation = Settings.System.getInt(context.getContentResolver(), Settings.System.USER_ROTATION, 0);
        SharedPreferences prefs = context.getSharedPreferences("catalog-ui", Context.MODE_PRIVATE);
        oldOffline = prefs.getBoolean("offline", false);
        // This prevents the production fragment from making live requests before fixture injection.
        prefs.edit().putBoolean("offline", true).commit();
        state.tabsOrder9 = "8#1,0#0,1#0,2#0,3#0,4#0,5#0,6#0,7#0";
        state.isSystemThemeColor = false;
        state.appLang = "ru";
    }

    @After public void tearDown() throws Exception {
        if (host != null) host.close();
        AppState state = AppState.get();
        state.tabsOrder9 = oldOrder; state.appTheme = oldTheme;
        state.appLang = oldLanguage;
        state.isDayNotInvert = oldDay; state.isSystemThemeColor = oldSystemTheme;
        context.getSharedPreferences("catalog-ui", Context.MODE_PRIVATE).edit().putBoolean("offline", oldOffline).commit();
        fontScale(oldFontScale);
        shell("cmd window user-rotation " + (oldAutoRotation == 1 ? "free" : "lock " + oldRotation));
    }

    @Test public void landscapeBookshelfAndDetailsStaySideBySide() throws Exception {
        launch(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, 1f, false);
        fixture(true);
        host.onActivity(activity -> {
            CatalogFragment fragment = catalog(activity);
            View master = value(fragment, "master", View.class);
            View detail = value(fragment, "detailScroll", View.class);
            assertEquals(View.VISIBLE, master.getVisibility());
            assertEquals(View.VISIBLE, detail.getVisibility());
            assertTrue("Tablet must show two genuine native panes", (Boolean) value(fragment, "dualPane", Object.class));
            assertTrue("Panes must not overlap", master.getRight() <= detail.getLeft());
            assertTrue("Details keep readable width", detail.getWidth() / density(activity) >= 350);
            View tabs = activity.findViewById(R.id.slidingTabs2);
            if (tabs != null && tabs.getVisibility() == View.VISIBLE) {
                int[] panePoint = new int[2], tabPoint = new int[2];
                detail.getLocationOnScreen(panePoint); tabs.getLocationOnScreen(tabPoint);
                assertTrue("App tabs cannot cover the book pane", panePoint[1] + detail.getHeight() <= tabPoint[1]);
            }
            assertControlsFit(fragment.requireView());
        });
        screenshot("tablet-landscape-bookshelf");
        host.onActivity(activity -> {
            CatalogFragment fragment = catalog(activity);
            value(fragment, "search", EditText.class).setText("Пушкин — сохранённый запрос");
            value(fragment, "detailScroll", ScrollView.class).scrollTo(0, 200);
            assertEquals(200, value(fragment, "detailScroll", ScrollView.class).getScrollY());
        });
        rotate(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        awaitOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        instrumentation.waitForIdleSync();
        host.onActivity(activity -> {
            CatalogFragment fragment = catalog(activity);
            assertEquals("Пушкин — сохранённый запрос", value(fragment, "search", EditText.class).getText().toString());
            assertNotNull("Selected book survives rotation", value(fragment, "selected", CatalogEntry.class));
            assertFalse("Rotating narrows the actual layout", (Boolean) value(fragment, "dualPane", Object.class));
            ScrollView detail = value(fragment, "detailScroll", ScrollView.class);
            int range = Math.max(0, detail.getChildAt(0).getHeight() - detail.getHeight()
                    + detail.getPaddingTop() + detail.getPaddingBottom());
            assertEquals("Book detail scroll survives or clamps to the taller viewport", Math.min(200, range), detail.getScrollY());
            assertControlsFit(fragment.requireView());
        });
    }

    @Test public void portraitBookshelfAndBookDetailsReflow() throws Exception {
        launch(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, 1f, false);
        fixture(false);
        host.onActivity(activity -> {
            CatalogFragment fragment = catalog(activity);
            assertFalse("800dp portrait uses a full-width shelf", (Boolean) value(fragment, "dualPane", Object.class));
            assertControlsFit(fragment.requireView());
        });
        screenshot("tablet-portrait-bookshelf");
        fixture(true);
        host.onActivity(activity -> {
            CatalogFragment fragment = catalog(activity);
            assertEquals(View.GONE, value(fragment, "master", View.class).getVisibility());
            assertEquals(View.VISIBLE, value(fragment, "detailScroll", View.class).getVisibility());
            assertControlsFit(fragment.requireView());
            assertTrue("Back returns to the shelf", fragment.isBackPressed());
            assertEquals(View.VISIBLE, value(fragment, "master", View.class).getVisibility());
        });
        fixture(true);
        screenshot("tablet-portrait-book");
    }

    @Test public void largeTextDarkThemeAndOfflineResultsRemainUsable() throws Exception {
        launch(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, 2f, true);
        fixture(true);
        host.onActivity(activity -> {
            CatalogFragment fragment = catalog(activity);
            assertTrue(activity.getResources().getConfiguration().fontScale >= 1.9f);
            assertControlsFit(fragment.requireView());
            TextView status = value(fragment, "statusText", TextView.class);
            assertTrue(status.getText().toString().contains(activity.getString(R.string.catalog_offline)));
            Button download = value(fragment, "primary", Button.class);
            assertNotNull(download);
            assertFalse("Manual offline mode cannot start a network transfer", download.isEnabled());
        });
        screenshot("tablet-landscape-dark-large-text-offline");
    }

    private void launch(int orientation, float scale, boolean dark) throws Exception {
        fontScale(scale);
        AppState.get().appTheme = dark ? AppState.THEME_DARK : AppState.THEME_LIGHT;
        AppState.get().isDayNotInvert = !dark;
        Intent intent = new Intent(context, MainTabs2.class)
                .putExtra(MainTabs2.EXTRA_SHOW_TABS, true)
                .putExtra(MainTabs2.EXTRA_PAGE_NUMBER, 0);
        host = ActivityScenario.launch(intent);
        rotate(orientation);
        instrumentation.waitForIdleSync();
        awaitOrientation(orientation);
    }

    private void awaitOrientation(int orientation) {
        // Orientation is handled by MainTabs2's existing configChanges path.
        for (int i = 0; i < 80; i++) {
            AtomicReference<Boolean> ready = new AtomicReference<>(false);
            host.onActivity(activity -> {
                CatalogFragment fragment = findCatalog(activity);
                if (fragment != null && fragment.getView() != null) {
                    View view = fragment.getView();
                    boolean shape = orientation == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                            ? view.getWidth() > view.getHeight() : view.getHeight() > view.getWidth();
                    ready.set(view.getWidth() > 0 && shape);
                }
            });
            if (ready.get()) return;
            SystemClock.sleep(100);
        }
        fail("Catalog did not reach the requested tablet orientation");
    }

    private void rotate(int orientation) throws Exception {
        // Android 16 deliberately ignores fixed activity orientation on large screens.
        // Rotate the actual tablet display, as a person rotating the device would.
        shell("cmd window user-rotation lock " + (orientation == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT ? 1 : 0));
    }

    private void fixture(boolean select) {
        host.onActivity(activity -> {
            CatalogFragment fragment = catalog(activity);
            Future<?> request = value(fragment, "requestFuture", Future.class);
            if (request != null) request.cancel(true);
            value(fragment, "repository", CatalogRepository.class).cancel();
            set(fragment, "generation", (Integer) value(fragment, "generation", Object.class) + 1);
            set(fragment, "busy", false); set(fragment, "error", null);
            CatalogPage feed = new CatalogPage();
            feed.title = "Книжная полка"; feed.url = "https://flibusta.is/opds/new";
            feed.fromCache = true; feed.fetchedAt = 1793577600000L;
            String[] titles = {"Капитанская дочка: повесть Александра Сергеевича Пушкина с примечаниями и дополнительными материалами", "Белые ночи", "Палата № 6", "Обломов", "Евгений Онегин", "Мёртвые души"};
            String[] authors = {"Александр Сергеевич Пушкин", "Фёдор Достоевский", "Антон Чехов", "Иван Гончаров", "Александр Пушкин", "Николай Гоголь"};
            for (int i = 0; i < titles.length; i++) {
                CatalogEntry entry = new CatalogEntry();
                entry.id = "tablet-fixture-" + i; entry.title = titles[i]; entry.author = authors[i];
                entry.summary = "Проверка настоящего интерфейса на планшете: длинное название, описание и доступные форматы должны оставаться читаемыми. Выбор книги сохраняет положение списка, а карточка использует свободное место рядом с полкой.";
                for (String format : new String[]{"EPUB", "FB2", "PDF"}) {
                    CatalogAcquisition link = new CatalogAcquisition(); link.format = format;
                    link.url = "https://flibusta.is/b/tablet-fixture/" + format.toLowerCase(java.util.Locale.ROOT);
                    link.mimeType = "application/octet-stream"; entry.acquisitions.add(link);
                }
                feed.entries.add(entry);
            }
            set(fragment, "page", feed);
            set(fragment, "selected", select ? feed.entries.get(0) : null);
            set(fragment, "selectedFormat", select ? feed.entries.get(0).acquisitions.get(0) : null);
            invoke(fragment, "showSection");
        });
        instrumentation.waitForIdleSync();
        SystemClock.sleep(250);
    }

    private void assertControlsFit(View root) {
        int[] origin = new int[2]; root.getLocationOnScreen(origin);
        inspect(root, origin[0], origin[0] + root.getWidth());
        assertJacketWords(root);
    }

    private void assertJacketWords(View view) {
        if (view.getVisibility() != View.VISIBLE || view.getWidth() == 0) return;
        if (view instanceof TextView && "Капитанская дочка".contentEquals(((TextView) view).getText())) {
            TextView jacket = (TextView) view;
            android.text.Layout textLayout = jacket.getLayout();
            assertNotNull("Jacket typography is laid out", textLayout);
            String firstLine = jacket.getText().subSequence(textLayout.getLineStart(0), textLayout.getLineEnd(0)).toString().trim();
            assertTrue("Short jacket words must fit whole: " + firstLine, firstLine.contains("Капитанская"));
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) assertJacketWords(group.getChildAt(i));
        }
    }

    private void inspect(View view, int left, int right) {
        if (view.getVisibility() != View.VISIBLE || view.getWidth() == 0) return;
        if (view instanceof Button) {
            int[] point = new int[2]; view.getLocationOnScreen(point);
            String label = ((Button) view).getText().toString();
            assertTrue("Action crosses left edge: " + label, point[0] >= left - 1);
            assertTrue("Action crosses right edge: " + label, point[0] + view.getWidth() <= right + 1);
            assertTrue("Touch target too short: " + label, view.getHeight() / density(view.getContext()) >= 47);
            android.text.Layout layout = ((Button) view).getLayout();
            if (layout != null) for (int i = 0; i < layout.getLineCount(); i++) {
                assertEquals("Essential action is truncated: " + label, 0, layout.getEllipsisCount(i));
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) inspect(group.getChildAt(i), left, right);
        }
    }

    private void screenshot(String name) throws Exception {
        instrumentation.waitForIdleSync();
        Bitmap bitmap = instrumentation.getUiAutomation().takeScreenshot();
        assertNotNull("UiAutomation screenshot", bitmap);
        File dir = new File(context.getExternalFilesDir(null), "testscreens");
        assertTrue(dir.isDirectory() || dir.mkdirs());
        try (FileOutputStream output = new FileOutputStream(new File(dir, name + ".png"))) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
        } finally { bitmap.recycle(); }
    }

    private void fontScale(float value) throws Exception {
        shell("settings put system font_scale " + value);
        SystemClock.sleep(300);
    }

    private void shell(String command) throws Exception {
        try (ParcelFileDescriptor fd = instrumentation.getUiAutomation().executeShellCommand(command);
             java.io.InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(fd)) {
            byte[] buffer = new byte[256]; while (input.read(buffer) != -1) { /* Wait for command completion. */ }
        }
    }

    private static CatalogFragment findCatalog(MainTabs2 activity) {
        for (Fragment fragment : activity.getSupportFragmentManager().getFragments()) {
            if (fragment instanceof CatalogFragment) return (CatalogFragment) fragment;
        }
        return null;
    }
    private static CatalogFragment catalog(MainTabs2 activity) {
        CatalogFragment fragment = findCatalog(activity); assertNotNull("Real catalog fragment", fragment); return fragment;
    }
    private static float density(Context context) { return context.getResources().getDisplayMetrics().density; }
    private static <T> T value(Object target, String name, Class<T> type) {
        try { Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return (T) field.get(target); }
        catch (Exception error) { throw new AssertionError(error); }
    }
    private static void set(Object target, String name, Object value) {
        try { Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target, value); }
        catch (Exception error) { throw new AssertionError(error); }
    }
    private static void invoke(Object target, String name) {
        try { Method method = target.getClass().getDeclaredMethod(name); method.setAccessible(true); method.invoke(target); }
        catch (Exception error) { throw new AssertionError(error); }
    }
}
