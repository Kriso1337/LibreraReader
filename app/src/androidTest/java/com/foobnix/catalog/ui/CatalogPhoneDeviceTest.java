package com.foobnix.catalog.ui;

import static org.junit.Assert.*;

import android.app.Instrumentation;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Handler;
import android.os.LocaleList;
import android.os.ParcelFileDescriptor;
import android.provider.Settings;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.foobnix.catalog.*;
import com.foobnix.model.AppProfile;
import com.foobnix.model.AppState;
import com.foobnix.pdf.info.R;
import com.foobnix.pdf.SlidingTabLayout;
import androidx.viewpager.widget.ViewPager;
import com.foobnix.pdf.info.model.BookCSS;
import com.foobnix.pdf.info.wrapper.UITab;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/** Physical-phone acceptance. Fixtures remain in memory; no download or system settings changes. */
@RunWith(AndroidJUnit4.class)
public class CatalogPhoneDeviceTest {
    private Instrumentation instrumentation;
    private Context context;
    private ActivityScenario<MainTabs2> host;
    private String oldLanguage;
    private int oldTheme, oldRotation, oldAutoRotation;
    private boolean oldDay, oldSystemTheme, oldOffline, hadOffline;
    private float oldAppScale;
    private LocaleList oldLocales;
    private Map<String, ?> oldCatalogPreferences;

    @Before public void setUp() {
        instrumentation = InstrumentationRegistry.getInstrumentation();
        context = instrumentation.getTargetContext();
        AppProfile.init(context);
        oldRotation = Settings.System.getInt(context.getContentResolver(), Settings.System.USER_ROTATION, 0);
        oldAutoRotation = Settings.System.getInt(context.getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, 1);
        AppState state = AppState.get();
        oldLanguage = state.appLang; oldTheme = state.appTheme;
        oldDay = state.isDayNotInvert; oldSystemTheme = state.isSystemThemeColor;
        oldAppScale = BookCSS.get().appFontScale; oldLocales = LocaleList.getDefault();
        SharedPreferences prefs = context.getSharedPreferences("catalog-ui", Context.MODE_PRIVATE);
        oldCatalogPreferences = new HashMap<>(prefs.getAll());
        hadOffline = prefs.contains("offline"); oldOffline = prefs.getBoolean("offline", false);
        prefs.edit().putBoolean("offline", true).remove("listMode").remove("filterAuthor")
                .remove("filterFormat").remove("filterDownloaded").remove("filterSort").commit();
        state.isSystemThemeColor = false; state.appLang = "ru";
    }

    @After public void restoreAppPreferences() throws Exception {
        // Restore before closing: MainTabs2.onPause persists the profile.
        AppState state = AppState.get();
        state.appLang = oldLanguage; state.appTheme = oldTheme;
        state.isDayNotInvert = oldDay; state.isSystemThemeColor = oldSystemTheme;
        BookCSS.get().appFontScale = oldAppScale;
        SharedPreferences.Editor editor = context.getSharedPreferences("catalog-ui", Context.MODE_PRIVATE).edit();
        if (hadOffline) editor.putBoolean("offline", oldOffline); else editor.remove("offline");
        for (String key : new String[]{"listMode", "filterAuthor", "filterFormat", "filterDownloaded", "filterSort"}) {
            Object value = oldCatalogPreferences.get(key);
            if (value instanceof String) editor.putString(key, (String) value);
            else if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
            else if (value instanceof Integer) editor.putInt(key, (Integer) value);
            else editor.remove(key);
        }
        editor.commit();
        if (host != null) {
            host.onActivity(activity -> {
                View focus = activity.getCurrentFocus();
                if (focus != null) ((InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE))
                        .hideSoftInputFromWindow(focus.getWindowToken(), 0);
            });
            host.close();
        }
        LocaleList.setDefault(oldLocales);
        if (Settings.System.getInt(context.getContentResolver(), Settings.System.USER_ROTATION, 0) != oldRotation
                || Settings.System.getInt(context.getContentResolver(), Settings.System.ACCELEROMETER_ROTATION, 1) != oldAutoRotation) {
            restoreRotation("cmd window user-rotation lock " + oldRotation);
            if (oldAutoRotation == 1) restoreRotation("cmd window user-rotation free");
        }
    }

    private void restoreRotation(String command) throws Exception {
        try (ParcelFileDescriptor fd = instrumentation.getUiAutomation().executeShellCommand(command);
                java.io.InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(fd)) {
            byte[] buffer = new byte[256]; while (input.read(buffer) != -1) { }
        }
    }

    @Test public void portraitShelfKeyboardDetailsAndFailedQueueAreUsable() throws Exception {
        launch(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, 1f, false);
        fixture(false, false); checkGeometry(); screenshot("phone-portrait-shelf");
        host.onActivity(activity -> {
            EditText search = field(catalog(activity), "search", EditText.class);
            search.requestFocus(); search.setText("Пушкин — длинный поисковый запрос");
            ((InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE))
                    .showSoftInput(search, InputMethodManager.SHOW_IMPLICIT);
        });
        awaitKeyboard(true);
        host.onActivity(activity -> {
            CatalogFragment fragment = catalog(activity);
            EditText search = field(fragment, "search", EditText.class);
            WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(activity.getWindow().getDecorView());
            assertNotNull(insets);
            int windowBottom = android.os.Build.VERSION.SDK_INT >= 30
                    ? activity.getWindowManager().getCurrentWindowMetrics().getBounds().bottom
                    : bounds(activity.getWindow().getDecorView()).bottom;
            int keyboardTop = windowBottom - insets.getInsets(WindowInsetsCompat.Type.ime()).bottom;
            assertTrue("Search must remain above keyboard", bounds(search).bottom <= keyboardTop + 1);
            assertFullyVisible(search);
            assertControls(fragment.requireView());
        });
        screenshot("phone-portrait-keyboard");
        host.onActivity(activity -> {
            EditText search = field(catalog(activity), "search", EditText.class);
            ((InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE))
                    .hideSoftInputFromWindow(search.getWindowToken(), 0);
        });
        awaitKeyboard(false);
        fixture(true, false); checkGeometry(); screenshot("phone-portrait-book");
        revealPrimary(); revealFolder(); screenshot("phone-portrait-book-actions");
        fixture(false, true); checkGeometry(); screenshot("phone-portrait-downloads");
    }

    @Test public void landscapeRetainsReadableViewportAndReachableActions() throws Exception {
        launch(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, 1f, false);
        fixture(false, false); checkGeometry(); screenshot("phone-landscape-shelf");
        host.onActivity(activity -> {
            CatalogFragment fragment = catalog(activity);
            assertTrue("Landscape needs usable content below navigation",
                    field(fragment, "body", View.class).getHeight() / density(activity) >= 120);
            RecyclerView list = field(fragment, "recyclerView", RecyclerView.class);
            assertNotNull("A book row is displayed", list.getChildAt(0));
            assertTrue("A landscape book card must fit the viewport: card=" + list.getChildAt(0).getHeight()
                            + ", list=" + list.getHeight() + ", padding=" + (list.getPaddingTop() + list.getPaddingBottom())
                            + ", compact=" + field(fragment, "compactBooks", Object.class)
                            + ", orientation=" + activity.getResources().getConfiguration().orientation,
                    list.getChildAt(0).getHeight() <= list.getHeight() - list.getPaddingTop() - list.getPaddingBottom() + 1);
        });
        screenshot("phone-landscape-shelf");
        fixture(true, false); checkGeometry(); revealPrimary(); revealFolder(); screenshot("phone-landscape-book-actions");
    }
    @Test public void portraitCatalogFitsThreeCompleteRowsBelowCompactControls() throws Exception {
        launch(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,1.15f,false);
        fixture(false,false,12);
        host.onActivity(activity->{
            CatalogFragment fragment=catalog(activity);
            CatalogEntry navigation=new CatalogEntry();navigation.id="navigation-fixture";
            navigation.title="Страница книги с подробным описанием";
            navigation.summary="Подробное описание из каталога. ".repeat(24);
            navigation.navigationUrl="https://flibusta.is/opds/book-fixture";
            field(fragment,"page",CatalogPage.class).entries.add(0,navigation);
            invoke(fragment,"showSection",new Class[0]);
        });
        settle();checkGeometry();
        host.onActivity(activity->{
            CatalogFragment fragment=catalog(activity);
            RecyclerView list=field(fragment,"recyclerView",RecyclerView.class);
            androidx.recyclerview.widget.GridLayoutManager grid=field(fragment,"layout",androidx.recyclerview.widget.GridLayoutManager.class);
            View thirdRow=grid.findViewByPosition(grid.getSpanCount()*3-1);
            assertNotNull("Three book rows are laid out",thirdRow);
            assertTrue("Three whole book rows fit above navigation",bounds(thirdRow).bottom<=bounds(list).bottom-list.getPaddingBottom());
            assertTrue("Controls reserve most of the viewport for books",list.getHeight()>=fragment.requireView().getHeight()*.55f);
        });
        screenshot("phone-catalog-three-rows");
        host.onActivity(activity->{
            CatalogFragment fragment=catalog(activity);
            RecyclerView list=field(fragment,"recyclerView",RecyclerView.class);
            assertTrue("The navigation card remains actionable",list.getLayoutManager().findViewByPosition(0).performClick());
            Object destination=field(fragment,"location",Object.class);
            assertEquals("https://flibusta.is/opds/book-fixture",field(destination,"url",String.class));
        });
    }

    @Test public void twoHundredPercentDarkTextDoesNotMergeControls() throws Exception {
        launch(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, 2f, true);
        fixture(false, false); checkGeometry(); screenshot("phone-dark-large-text-shelf");
        host.onActivity(activity -> assertTrue(activity.getResources().getConfiguration().fontScale >= 1.9f));
        fixture(true, false); checkGeometry(); revealPrimary(); revealFolder(); screenshot("phone-dark-large-text-actions");
        fixture(false, true); checkGeometry(); screenshot("phone-dark-large-text-downloads");
    }

    @Test public void listAndFiltersRemainUsableWithLargeText() throws Exception {
        launch(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, 2f, true);
        fixture(false, false);
        host.onActivity(activity -> invoke(catalog(activity), "toggleView", new Class[0]));
        settle(); checkGeometry(); screenshot("phone-catalog-list");
        final AlertDialog[] dialog = new AlertDialog[1];
        host.onActivity(activity -> {
            CatalogFragment fragment = catalog(activity);
            assertEquals(1, field(fragment, "layout", androidx.recyclerview.widget.GridLayoutManager.class).getSpanCount());
            try {
                Method method = fragment.getClass().getDeclaredMethod("chooseFilters"); method.setAccessible(true);
                dialog[0] = (AlertDialog) method.invoke(fragment);
            } catch (Exception failure) { throw new AssertionError(failure); }
        });
        settle(); screenshot("phone-catalog-filters");
        host.onActivity(activity -> {
            View decor = dialog[0].getWindow().getDecorView();
            EditText author = findType(decor, EditText.class); assertNotNull(author); author.setText("Пушкин");
            Button apply = findButton(decor, activity.getString(R.string.apply)); assertNotNull(apply);
            apply.requestRectangleOnScreen(new Rect(0,0,apply.getWidth(),apply.getHeight()), true);
        });
        settle(); screenshot("phone-catalog-filter-actions");
        host.onActivity(activity -> {
            Button apply = findButton(dialog[0].getWindow().getDecorView(), activity.getString(R.string.apply));
            assertFullyVisible(apply); apply.performClick();
        });
        settle();
        host.onActivity(activity -> {
            RecyclerView list = field(catalog(activity), "recyclerView", RecyclerView.class);
            assertEquals("Author filter changes displayed books", 1, list.getAdapter().getItemCount());
            assertTrue("List preference persists", context.getSharedPreferences("catalog-ui", Context.MODE_PRIVATE).getBoolean("listMode",false));
        });
        screenshot("phone-catalog-filtered-list");
        host.onActivity(activity -> {
            CatalogFragment fragment = catalog(activity);
            try {
                Class<?> location = Class.forName("com.foobnix.ui2.fragment.CatalogFragment$Location");
                java.lang.reflect.Constructor<?> constructor = location.getDeclaredConstructor(String.class,String.class,boolean.class);
                constructor.setAccessible(true);
                Object missing = constructor.newInstance("https://flibusta.is/opds/acceptance-missing-page", "", false);
                invoke(fragment,"request",new Class[]{location,boolean.class},missing,false);
                assertNull("Back/navigation must not leave books from a different page",field(fragment,"page",Object.class));
            } catch (Exception failure) { throw new AssertionError(failure); }
        });
    }

    private static <T extends View> T findType(View root, Class<T> type) {
        if (type.isInstance(root)) return type.cast(root);
        if (root instanceof ViewGroup) for (int i=0;i<((ViewGroup)root).getChildCount();i++) {
            T found=findType(((ViewGroup)root).getChildAt(i),type); if(found!=null)return found;
        }
        return null;
    }
    private static Button findButton(View root, String label) {
        if (root instanceof Button && label.contentEquals(((Button)root).getText()))return (Button)root;
        if (root instanceof ViewGroup) for (int i=0;i<((ViewGroup)root).getChildCount();i++) {
            Button found=findButton(((ViewGroup)root).getChildAt(i),label); if(found!=null)return found;
        }
        return null;
    }

    private void launch(int orientation, float scale, boolean dark) {
        BookCSS.get().appFontScale = scale;
        AppState.get().appTheme = dark ? AppState.THEME_DARK : AppState.THEME_LIGHT;
        AppState.get().isDayNotInvert = !dark;
        int tab = UITab.getCurrentTabIndex(UITab.Catalog);
        assertTrue("Catalog must be visible in the existing tab order", tab >= 0);
        host = ActivityScenario.launch(new Intent(context, MainTabs2.class)
                .putExtra(MainTabs2.EXTRA_SHOW_TABS, true).putExtra(MainTabs2.EXTRA_PAGE_NUMBER, tab));
        host.onActivity(activity -> activity.setRequestedOrientation(orientation));
        for (int i = 0; i < 100; i++) {
            AtomicBoolean ready = new AtomicBoolean();
            host.onActivity(activity -> {
                CatalogFragment fragment = findCatalog(activity);
                if (fragment != null && fragment.getView() != null) {
                    View view = fragment.requireView();
                    ready.set(view.getWidth() > 0 && (orientation == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                            ? view.getWidth() > view.getHeight() : view.getHeight() > view.getWidth()));
                }
            });
            if (ready.get()) { settle(); return; }
            SystemClock.sleep(100);
        }
        fail("Phone did not reach requested orientation");
    }

    private void fixture(boolean selected, boolean queue) {
        fixture(selected,queue,4);
    }

    private void fixture(boolean selected, boolean queue, int bookCount) {
        host.onActivity(activity -> {
            CatalogFragment fragment = catalog(activity);
            Future<?> pending = field(fragment, "requestFuture", Future.class);
            if (pending != null) pending.cancel(true);
            field(fragment, "repository", CatalogRepository.class).cancel();
            field(fragment, "ui", Handler.class).removeCallbacks(field(fragment, "poll", Runnable.class));
            put(fragment, "polling", true);
            put(fragment, "generation", (Integer) field(fragment, "generation", Object.class) + 1);
            put(fragment, "busy", false); put(fragment, "error", null);
            CatalogPage page = new CatalogPage(); page.title = "Книжная полка";
            page.url = "https://flibusta.is/opds/new"; page.fromCache = true; page.fetchedAt = 1793577600000L;
            String[] titles = {"Капитанская дочка: повесть с примечаниями и дополнительными материалами", "Белые ночи", "Палата № 6", "Обломов"};
            String[] authors = {"Александр Сергеевич Пушкин", "Фёдор Достоевский", "Антон Павлович Чехов", "Иван Гончаров"};
            for (int i = 0; i < bookCount; i++) {
                CatalogEntry book = new CatalogEntry(); book.id = "phone-fixture-" + i; book.title = titles[i%titles.length]; book.author = authors[i%authors.length];
                book.summary = "Длинное описание проверяет читаемость текста и прокрутку карточки на узком экране. Все форматы, кнопка загрузки и выбор папки должны оставаться доступными.";
                for (String type : new String[]{"EPUB", "FB2.ZIP", "PDF"}) {
                    CatalogAcquisition format = new CatalogAcquisition(); format.format = type;
                    format.url = "https://flibusta.is/b/phone-fixture/" + type.toLowerCase(Locale.ROOT);
                    format.mimeType = "application/octet-stream"; book.acquisitions.add(format);
                }
                page.entries.add(book);
            }
            List<CatalogDownloads.Task> tasks = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                CatalogDownloads.Task task = new CatalogDownloads.Task(); task.id = "phone-task-" + i;
                task.bookId = "phone-queue-" + i; task.title = titles[i]; task.author = authors[i];
                task.status = CatalogDownloads.FAILED; task.format = "EPUB";
                task.error = "Не удалось сохранить книгу. Проверьте доступ к выбранной папке."; tasks.add(task);
            }
            put(fragment, "tasks", tasks); put(fragment, "page", page);
            invoke(fragment, "switchSection", new Class[]{String.class}, queue ? "downloads" : "catalog");
            put(fragment, "selected", selected ? page.entries.get(0) : null);
            put(fragment, "selectedFormat", selected ? page.entries.get(0).acquisitions.get(0) : null);
            invoke(fragment, "showSection", new Class[0]);
        });
        settle();
    }

    private void checkGeometry() {
        host.onActivity(activity -> {
            CatalogFragment fragment = catalog(activity); assertControls(fragment.requireView());
            SlidingTabLayout tabs = activity.findViewById(R.id.slidingTabs2);
            ViewPager pager = activity.findViewById(R.id.pager);
            if (tabs != null && tabs.isShown() && pager != null) {
                View selectedTab = tabs.getmTabStrip().getChildAt(pager.getCurrentItem());
                Rect visibleTab = new Rect();
                assertTrue("Current app tab must be visible", selectedTab.getGlobalVisibleRect(visibleTab));
                assertTrue("Current app tab must not be clipped", visibleTab.width() >= selectedTab.getWidth() - 1);
            }
            View header = field(fragment, "header", View.class);
            assertWordsUnbroken(header, activity.getString(R.string.library));
            RecyclerView list = field(fragment, "recyclerView", RecyclerView.class);
            if ("downloads".equals(field(fragment, "section", String.class)) && list.getChildCount() >= 2) {
                View first = list.getChildAt(0), second = list.getChildAt(1);
                assertTrue("Download cards need a visible gap", second.getTop() - first.getBottom() >= 4 * density(activity) - 1);
            }
        });
    }

    private void revealPrimary() {
        host.onActivity(activity -> {
            Button button = field(catalog(activity), "primary", Button.class);
            assertNotNull(button); button.requestRectangleOnScreen(new Rect(0, 0, button.getWidth(), button.getHeight()), true);
        });
        settle();
        host.onActivity(activity -> assertFullyVisible(field(catalog(activity), "primary", Button.class)));
    }

    private void revealFolder() {
        host.onActivity(activity -> {
            LinearLayout detail = field(catalog(activity), "detailBox", LinearLayout.class);
            View folder = detail.getChildAt(detail.getChildCount() - 1);
            assertTrue("Storage action is present", folder instanceof Button);
            folder.requestRectangleOnScreen(new Rect(0, 0, folder.getWidth(), folder.getHeight()), true);
        });
        settle();
        host.onActivity(activity -> {
            LinearLayout detail = field(catalog(activity), "detailBox", LinearLayout.class);
            assertFullyVisible(detail.getChildAt(detail.getChildCount() - 1));
        });
    }

    private void assertControls(View root) {
        Rect rootBounds = bounds(root); List<View> actions = new ArrayList<>(); collect(root, actions);
        for (View view : actions) {
            Rect box = bounds(view); float dp = density(view.getContext());
            assertTrue("Touch target below 48dp: " + label(view), view.getHeight() >= 47 * dp && view.getWidth() >= 47 * dp);
            if (!insideHorizontalScroller(view)) assertTrue("Action crosses viewport: " + label(view), box.left >= rootBounds.left - 1 && box.right <= rootBounds.right + 1);
            if (view instanceof TextView) {
                TextView text = (TextView) view; android.text.Layout layout = text.getLayout();
                if (layout != null) {
                    for (int i = 0; i < layout.getLineCount(); i++) assertEquals("Truncated action: " + label(view), 0, layout.getEllipsisCount(i));
                    assertTrue("Action text clipped vertically: " + label(view), layout.getHeight() + text.getCompoundPaddingTop() + text.getCompoundPaddingBottom() <= view.getHeight() + 1);
                }
            }
        }
        for (int i = 0; i < actions.size(); i++) for (int j = i + 1; j < actions.size(); j++) {
            Rect a = new Rect(), b = new Rect();
            if (actions.get(i).getGlobalVisibleRect(a) && actions.get(j).getGlobalVisibleRect(b))
                assertFalse("Controls overlap: " + label(actions.get(i)) + " / " + label(actions.get(j)), Rect.intersects(a, b));
        }
        checkSiblingGaps(root);
    }

    private void checkSiblingGaps(View view) {
        if (!(view instanceof ViewGroup) || view.getVisibility() != View.VISIBLE) return;
        ViewGroup group = (ViewGroup) view;
        for (int i = 1; i < group.getChildCount(); i++) {
            View a = group.getChildAt(i - 1), b = group.getChildAt(i);
            if (a instanceof Button && b instanceof Button && a.getVisibility() == View.VISIBLE && b.getVisibility() == View.VISIBLE && group instanceof LinearLayout) {
                int gap = ((LinearLayout) group).getOrientation() == LinearLayout.HORIZONTAL ? b.getLeft() - a.getRight() : b.getTop() - a.getBottom();
                assertTrue("Adjacent controls need separation: " + label(a) + " / " + label(b), gap >= 4 * density(view.getContext()) - 1);
            }
        }
        for (int i = 0; i < group.getChildCount(); i++) checkSiblingGaps(group.getChildAt(i));
    }
    private void collect(View view, List<View> actions) {
        if (view.getVisibility() != View.VISIBLE || view.getWidth() == 0) return;
        if (view instanceof Button || view instanceof EditText) actions.add(view);
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) collect(((ViewGroup) view).getChildAt(i), actions);
    }
    private void assertWordsUnbroken(View view, String word) {
        if (view.getVisibility() != View.VISIBLE) return;
        if (view instanceof Button && word.contentEquals(((Button) view).getText())) assertEquals("Section label must not split a word", 1, ((Button) view).getLayout().getLineCount());
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) assertWordsUnbroken(((ViewGroup) view).getChildAt(i), word);
    }
    private void assertFullyVisible(View view) {
        Rect visible = new Rect(); assertTrue("Control must be reachable", view.getGlobalVisibleRect(visible));
        assertTrue("Control must fit visible viewport: " + label(view), visible.width() >= view.getWidth() - 1 && visible.height() >= view.getHeight() - 1);
    }
    private boolean insideHorizontalScroller(View view) {
        for (android.view.ViewParent p = view.getParent(); p instanceof View; p = p.getParent()) if (p instanceof HorizontalScrollView) return true;
        return false;
    }
    private void awaitKeyboard(boolean shown) {
        for (int i = 0; i < 50; i++) {
            AtomicBoolean ready = new AtomicBoolean(); host.onActivity(activity -> {
                WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(activity.getWindow().getDecorView());
                ready.set(insets != null && insets.isVisible(WindowInsetsCompat.Type.ime()) == shown);
            });
            if (ready.get()) { settle(); return; } SystemClock.sleep(100);
        }
        fail("Keyboard visibility did not become " + shown);
    }
    private void screenshot(String name) throws Exception {
        instrumentation.waitForIdleSync(); Bitmap image = instrumentation.getUiAutomation().takeScreenshot(); assertNotNull(image);
        File folder = new File(context.getExternalFilesDir(null), "tests-phone"); assertTrue(folder.isDirectory() || folder.mkdirs());
        try (FileOutputStream output = new FileOutputStream(new File(folder, name + ".png"))) { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, output)); }
        finally { image.recycle(); }
    }
    private void settle() { instrumentation.waitForIdleSync(); SystemClock.sleep(250); }
    private static Rect bounds(View view) { int[] xy = new int[2]; view.getLocationOnScreen(xy); return new Rect(xy[0], xy[1], xy[0] + view.getWidth(), xy[1] + view.getHeight()); }
    private static String label(View view) { return view instanceof TextView ? ((TextView) view).getText().toString() : view.getClass().getSimpleName(); }
    private static float density(Context context) { return context.getResources().getDisplayMetrics().density; }
    private static CatalogFragment findCatalog(MainTabs2 activity) { for (Fragment f : activity.getSupportFragmentManager().getFragments()) if (f instanceof CatalogFragment) return (CatalogFragment) f; return null; }
    private static CatalogFragment catalog(MainTabs2 activity) { CatalogFragment fragment = findCatalog(activity); assertNotNull("Real catalog fragment", fragment); return fragment; }
    @SuppressWarnings("unchecked") private static <T> T field(Object target, String name, Class<T> type) {
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) try { Field f = c.getDeclaredField(name); f.setAccessible(true); return (T) f.get(target); } catch (NoSuchFieldException ignored) { } catch (Exception e) { throw new AssertionError(e); }
        throw new AssertionError("Missing field " + name);
    }
    private static void put(Object target, String name, Object value) { try { Field f = target.getClass().getDeclaredField(name); f.setAccessible(true); f.set(target, value); } catch (Exception e) { throw new AssertionError(e); } }
    private static void invoke(Object target, String name, Class<?>[] types, Object... args) { try { Method m = target.getClass().getDeclaredMethod(name, types); m.setAccessible(true); m.invoke(target, args); } catch (Exception e) { throw new AssertionError(e); } }
}
