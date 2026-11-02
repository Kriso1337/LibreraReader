package com.foobnix.ui2.fragment;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.provider.DocumentsContract;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.text.format.Formatter;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.graphics.Insets;
import androidx.core.util.Pair;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;
import com.foobnix.catalog.CatalogAcquisition;
import com.foobnix.catalog.CatalogCover;
import com.foobnix.catalog.CatalogCoverLoader;
import com.foobnix.catalog.CatalogDownloads;
import com.foobnix.catalog.CatalogEntry;
import com.foobnix.catalog.CatalogPage;
import com.foobnix.catalog.CatalogRepository;
import com.foobnix.catalog.ui.CatalogLayoutPolicy;
import com.foobnix.dao2.FileMeta;
import com.foobnix.model.AppState;
import com.foobnix.pdf.SlidingTabLayout;
import com.foobnix.pdf.info.ExtUtils;
import com.foobnix.pdf.info.R;
import com.foobnix.pdf.info.wrapper.DocumentController;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** A native, adaptive OPDS bookshelf. The reader and existing local library keep their identity. */
public class CatalogFragment extends UIFragment<CatalogEntry> {
    public static final Pair<Integer, Integer> PAIR = new Pair<>(R.string.catalog_tab_title, R.drawable.glyphicons_417_globe);
    private static final int PICK_FOLDER = 4018;
    private static final String CATALOG = "catalog", LOCAL = "local", DOWNLOADS = "downloads";
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private final ExecutorService commands = Executors.newSingleThreadExecutor();
    private CatalogRepository repository;
    private Future<?> requestFuture;
    private CatalogDownloads downloads;
    private SharedPreferences preferences;
    private LinearLayout root, header, body, master, detailBox, statusBox;
    private LinearLayout normalTop, searchRow, compactRow, masterHeading;
    private HorizontalScrollView sectionStrip;
    private View sectionGap, searchGap;
    private Button compactSearchMenu, statusRetry;
    private ScrollView detailScroll;
    private EditText search;
    private TextView pageTitle, statusText, detailStatus;
    private Button previous, next, primary, sourceButton;
    private ProgressBar loading, detailProgress;
    private GridLayoutManager layout;
    private ShelfAdapter adapter;
    private CatalogPage page;
    private CatalogEntry selected;
    private CatalogAcquisition selectedFormat;
    private List<CatalogDownloads.Task> tasks = Collections.emptyList();
    private Map<String, CatalogDownloads.Task> completed = Collections.emptyMap();
    private final ArrayList<Location> history = new ArrayList<>();
    private Location location = new Location(null, "", false);
    private String section = CATALOG, treeUri, searchDraft = "", error;
    private boolean offline, busy, dualPane, resumed, polling, rebuilding;
    private boolean ownsStatusIcons, previousLightStatusIcons, imeVisible, compactHeader, compactBooks, bookRefreshPosted;
    private volatile int generation, viewGeneration;
    private int detailWidth, lastLayoutWidth, lastLayoutHeight, savedDetailScroll, bookLayoutWidth;
    private Parcelable savedListState;
    private int paper, surface, ink, muted, accent, onAccent, line;

    private static class Location implements Serializable {
        final String url, query;
        final boolean search;
        Location(String url, String query, boolean search) {
            this.url = url; this.query = query; this.search = search;
        }
    }

    @Override public Pair<Integer, Integer> getNameAndIconRes() { return PAIR; }
    @Override public boolean hasFloatingHeader() { return false; }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        Context context = requireContext().getApplicationContext();
        CatalogCoverLoader.register(context);
        repository = new CatalogRepository(context);
        downloads = CatalogDownloads.get(context);
        preferences = context.getSharedPreferences("catalog-ui", Context.MODE_PRIVATE);
        treeUri = preferences.getString("tree", null);
        offline = preferences.getBoolean("offline", false);
        if (state != null) {
            section = state.getString("section", CATALOG);
            searchDraft = state.getString("draft", "");
            Location restored = (Location) state.getSerializable("location");
            if (restored != null) location = restored;
            ArrayList<Location> previous = (ArrayList<Location>) state.getSerializable("history");
            if (previous != null) history.addAll(previous);
            selected = (CatalogEntry) state.getSerializable("selected");
            selectedFormat = (CatalogAcquisition) state.getSerializable("format");
            savedListState = state.getParcelable("list");
            savedDetailScroll = state.getInt("detailScroll");
        }
    }

    @Override public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle state) {
        palette();
        root = column();
        root.setBackgroundColor(paper);
        root.setPadding(dp(16), dp(12), dp(16), dp(8));
        header = column(); root.addView(header, matchWrap());
        body = new LinearLayout(requireContext());
        body.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if ((r - l != lastLayoutWidth || b - t != lastLayoutHeight) && r > l) {
                lastLayoutWidth = r - l; lastLayoutHeight = b - t;
                applyAdaptiveLayout();
            }
        });
        createHeader();
        createBody();
        showSection();
        request(location, false);
        // UIFragment's legacy chrome routine assumes one scroller under one tinted
        // header. Both bookshelf panes must instead clear the app's floating tabs.
        FrameLayout frame = new FrameLayout(requireContext());
        frame.addView(root, new FrameLayout.LayoutParams(-1, -1));
        frame.getViewTreeObserver().addOnGlobalLayoutListener(() -> fitAroundChrome(frame));
        ViewCompat.setOnApplyWindowInsetsListener(frame, (view, insets) -> {
            fitAroundChrome(frame);
            return insets;
        });
        return frame;
    }

    private void fitAroundChrome(View attached) {
        if (root == null || getActivity() == null) return;
        updateStatusIcons(resumed && getUserVisibleHint());
        WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(attached);
        Insets safe = DocumentController.keptClearOf(getActivity(), insets);
        if (insets != null) safe = Insets.max(safe, insets.getInsets(WindowInsetsCompat.Type.displayCutout()));
        boolean keyboard = insets != null && insets.isVisible(WindowInsetsCompat.Type.ime());
        boolean keyboardChanged = imeVisible != keyboard;
        imeVisible = keyboard;
        View topTabs = getActivity().findViewById(R.id.imageParent1);
        int top = SlidingTabLayout.isFloating() ? safe.top : topTabs == null ? safe.top : topTabs.getHeight();
        int left = dp(16) + safe.left, right = dp(16) + safe.right;
        int bottom = dp(8) + safe.bottom + SlidingTabLayout.floatingSpace();
        if (imeVisible) {
            // The visible frame handles both edge-to-edge IME overlays and an already
            // resized window. Counting the entire IME inset twice would erase the list.
            Rect visible = new Rect(); attached.getWindowVisibleDisplayFrame(visible);
            int[] at = new int[2]; attached.getLocationOnScreen(at);
            int overlap = Math.max(0, at[1] + attached.getHeight() - visible.bottom);
            View tabs = getActivity().findViewById(R.id.slidingTabs2);
            int floating = 0;
            if (SlidingTabLayout.isFloating() && tabs != null && tabs.isShown()) {
                int[] tabAt = new int[2]; tabs.getLocationOnScreen(tabAt);
                if (tabAt[1] + tabs.getHeight() <= visible.bottom + 1) floating = SlidingTabLayout.floatingSpace();
            }
            bottom = dp(8) + Math.max(safe.bottom, overlap) + floating;
        }
        top += dp(12);
        if (root.getPaddingLeft() != left || root.getPaddingTop() != top
                || root.getPaddingRight() != right || root.getPaddingBottom() != bottom) {
            root.setPadding(left, top, right, bottom);
            applyAdaptiveLayout();
        } else if (keyboardChanged) applyAdaptiveLayout();
    }

    private void updateStatusIcons(boolean visible) {
        if (getActivity() == null) return;
        WindowInsetsControllerCompat bars = WindowCompat.getInsetsController(getActivity().getWindow(), getActivity().getWindow().getDecorView());
        if (visible && SlidingTabLayout.isFloating()) {
            if (!ownsStatusIcons) {
                previousLightStatusIcons = bars.isAppearanceLightStatusBars();
                ownsStatusIcons = true;
            }
            bars.setAppearanceLightStatusBars(AppState.get().appTheme != AppState.THEME_DARK
                    && AppState.get().appTheme != AppState.THEME_DARK_OLED);
        } else if (ownsStatusIcons) {
            bars.setAppearanceLightStatusBars(previousLightStatusIcons);
            ownsStatusIcons = false;
        }
    }

    private void palette() {
        boolean dark = AppState.get().appTheme == AppState.THEME_DARK
                || AppState.get().appTheme == AppState.THEME_DARK_OLED;
        paper = Color.parseColor(dark ? "#181E1B" : "#F7F5EE");
        surface = Color.parseColor(dark ? "#232C27" : "#FFFEF9");
        ink = Color.parseColor(dark ? "#EDF1E6" : "#243C31");
        muted = Color.parseColor(dark ? "#ABB9AD" : "#66746B");
        accent = Color.parseColor(dark ? "#B5D0BA" : "#244C3A");
        onAccent = Color.parseColor(dark ? "#173323" : "#FFFEF8");
        line = Color.parseColor(dark ? "#39473D" : "#DFE3D8");
    }

    private void createHeader() {
        header.removeAllViews();
        LinearLayout top = normalTop = row();
        TextView brand = text("Flibusta", 28, ink);
        brand.setTypeface(Typeface.create("serif", Typeface.NORMAL));
        top.addView(brand, new LinearLayout.LayoutParams(0, -2, 1));
        sourceButton = button(getString(R.string.server), false, v -> chooseSource());
        top.addView(sourceButton, wrapWrap());
        header.addView(top, matchWrap());
        LinearLayout sections = row();
        Button selectedSection = null;
        for (String name : new String[]{CATALOG, LOCAL, DOWNLOADS}) {
            int label = name.equals(CATALOG) ? R.string.catalog_tab_title : name.equals(LOCAL) ? R.string.library : R.string.downloads;
            Button item = button(getString(label), section.equals(name), v -> switchSection(name));
            item.setSingleLine(true); item.setEllipsize(null);
            item.setPadding(dp(10), dp(10), dp(10), dp(10));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2, 1);
            if (!DOWNLOADS.equals(name)) params.setMarginEnd(dp(8));
            sections.addView(item, params);
            if (section.equals(name)) selectedSection = item;
        }
        sectionGap = new View(requireContext()); header.addView(sectionGap, new LinearLayout.LayoutParams(1, dp(8)));
        sectionStrip = new HorizontalScrollView(requireContext());
        sectionStrip.setHorizontalScrollBarEnabled(false); sectionStrip.setFillViewport(true);
        sectionStrip.addView(sections, new HorizontalScrollView.LayoutParams(-2, -2));
        header.addView(sectionStrip, matchWrap());
        final HorizontalScrollView strip = sectionStrip;
        final Button activeSection = selectedSection;
        strip.addOnLayoutChangeListener((view, l, t, r, b, oldL, oldT, oldR, oldB) -> {
            // A posted callback can run before the first measure, when every right edge
            // is still zero. The selected segment must remain visible after rotation too.
            if (r > l && activeSection != null && activeSection.getWidth() > 0) {
                strip.scrollTo(Math.max(0, activeSection.getRight() - strip.getWidth()), 0);
            }
        });
        compactRow = row();
        Button compactMenu = button(sectionLabel() + " ▾", false, v -> chooseSection());
        compactMenu.setSingleLine(true);
        compactRow.addView(compactMenu, new LinearLayout.LayoutParams(0, -2, 1));
        Button compactSource = button(getString(R.string.server), false, v -> chooseSource());
        LinearLayout.LayoutParams compactSourceParams = wrapWrap(); compactSourceParams.setMarginStart(dp(8));
        compactRow.addView(compactSource, compactSourceParams);
        header.addView(compactRow, matchWrap()); compactRow.setVisibility(View.GONE);
        searchGap = null; searchRow = null; compactSearchMenu = null;
        if (CATALOG.equals(section)) {
            searchRow = row();
            compactSearchMenu = button(sectionLabel() + " ▾", false, v -> chooseSection());
            compactSearchMenu.setSingleLine(true); compactSearchMenu.setVisibility(View.GONE);
            LinearLayout.LayoutParams menuParams = wrapWrap(); menuParams.setMarginEnd(dp(8));
            searchRow.addView(compactSearchMenu, menuParams);
            search = new EditText(requireContext());
            search.setTextSize(16); search.setTextColor(ink); search.setHintTextColor(muted);
            search.setHint(R.string.search_in_the_library);
            search.setContentDescription(getString(R.string.search_in_the_library));
            search.setSingleLine(true); search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
            search.setMinHeight(dp(52)); search.setPadding(dp(16), dp(8), dp(16), dp(8));
            search.setBackground(shape(surface, 14, line));
            search.setText(searchDraft);
            search.setOnEditorActionListener((v, actionId, event) -> {
                if (actionId == EditorInfo.IME_ACTION_SEARCH) { submitSearch(); return true; }
                return false;
            });
            searchRow.addView(search, new LinearLayout.LayoutParams(0, -2, 1));
            Button go = button(getString(R.string.search), false, v -> submitSearch());
            LinearLayout.LayoutParams gp = wrapWrap(); gp.setMarginStart(dp(6));
            searchRow.addView(go, gp);
            searchGap = new View(requireContext()); header.addView(searchGap, new LinearLayout.LayoutParams(1, dp(12)));
            header.addView(searchRow, matchWrap());
        } else search = null;
        loading = new ProgressBar(requireContext(), null, android.R.attr.progressBarStyleHorizontal);
        loading.setIndeterminate(true); loading.setIndeterminateTintList(ColorStateList.valueOf(accent));
        loading.setVisibility(busy && CATALOG.equals(section) ? View.VISIBLE : View.INVISIBLE);
        header.addView(loading, new LinearLayout.LayoutParams(-1, dp(6)));
    }

    private void createBody() {
        body.removeAllViews();
        master = column();
        LinearLayout heading = masterHeading = row();
        previous = button(getString(R.string.back), false, v -> onBackAction());
        heading.addView(previous, wrapWrap());
        pageTitle = text("", 18, ink);
        pageTitle.setTypeface(Typeface.create("serif", Typeface.NORMAL));
        pageTitle.setPadding(dp(10), 0, 0, 0);
        heading.addView(pageTitle, new LinearLayout.LayoutParams(0, -2, 1));
        master.addView(heading, matchWrap());
        statusBox = column();
        statusBox.setPadding(dp(12), dp(8), dp(12), dp(8));
        statusBox.setBackground(shape(surface, 12, line));
        statusText = text("", 14, muted);
        statusText.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        statusBox.addView(statusText, matchWrap());
        statusRetry = button(getString(R.string.try_again), false, v -> retryPage());
        statusBox.addView(statusRetry, wrapWrap());
        master.addView(statusBox, matchWrap());
        recyclerView = new RecyclerView(requireContext());
        recyclerView.setClipToPadding(false);
        recyclerView.setPadding(0, dp(10), 0, dp(12));
        layout = new GridLayoutManager(requireContext(), 2);
        layout.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override public int getSpanSize(int position) {
                return adapter != null && adapter.isBook(position) ? 1 : layout.getSpanCount();
            }
        });
        recyclerView.setLayoutManager(layout);
        adapter = new ShelfAdapter(); recyclerView.setAdapter(adapter);
        master.addView(recyclerView, new LinearLayout.LayoutParams(-1, 0, 1));
        next = button(getString(R.string.next_page), false, v -> {
            if (page != null && !empty(page.nextUrl)) request(new Location(page.nextUrl, location.query, false), true);
        });
        master.addView(next, matchWrap());
        body.addView(master, new LinearLayout.LayoutParams(0, -1, 1));
        detailScroll = new ScrollView(requireContext());
        detailScroll.setFillViewport(false);
        detailScroll.setBackground(shape(surface, 20, line));
        detailBox = column(); detailBox.setPadding(dp(24), dp(20), dp(24), dp(24));
        detailScroll.addView(detailBox, new ScrollView.LayoutParams(-1, -2));
        body.addView(detailScroll, new LinearLayout.LayoutParams(dp(380), -1));
        rebuildDetail();
        applyAdaptiveLayout();
    }

    private void applyAdaptiveLayout() {
        if (root == null || master == null || rebuilding) return;
        rebuilding = true;
        float available = (root.getWidth() - root.getPaddingLeft() - root.getPaddingRight()) / density();
        if (available <= 0) available = getResources().getConfiguration().screenWidthDp - 32;
        float availableHeight = (root.getHeight() - root.getPaddingTop() - root.getPaddingBottom()) / density();
        if (root.getHeight() <= 0) availableHeight = getResources().getConfiguration().screenHeightDp - 100;
        compactHeader = availableHeight < 440;
        dualPane = CatalogLayoutPolicy.isTwoPane(available, availableHeight, getResources().getConfiguration().fontScale);
        detailWidth = CatalogLayoutPolicy.detailWidth(available);
        boolean showDetail = selected != null || (dualPane && !DOWNLOADS.equals(section));
        master.setVisibility(!dualPane && selected != null ? View.GONE : View.VISIBLE);
        detailScroll.setVisibility(showDetail ? View.VISIBLE : View.GONE);
        LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(dualPane ? dp(detailWidth) : 0, -1, dualPane ? 0 : 1);
        dp.setMarginStart(dualPane ? dp(20) : 0); detailScroll.setLayoutParams(dp);
        float listWidth = dualPane ? available - detailWidth - 20 : available;
        boolean previousCompactBooks = compactBooks;
        // A language-specific Configuration can retain its original orientation.
        // The measured window, including split-screen, is the layout authority.
        compactBooks = compactHeader && !dualPane && !imeVisible && available > availableHeight;
        int columns = compactBooks ? CatalogLayoutPolicy.compactColumns(listWidth, getResources().getConfiguration().fontScale)
                : CatalogLayoutPolicy.columns(listWidth, getResources().getConfiguration().fontScale);
        int width = Math.round(listWidth * density());
        boolean changedBooks = previousCompactBooks != compactBooks || bookLayoutWidth != width
                || layout.getSpanCount() != columns;
        bookLayoutWidth = width;
        if (layout.getSpanCount() != columns) layout.setSpanCount(columns);
        if (changedBooks) rebindBooksAfterLayout();
        boolean singleDetail = selected != null && !dualPane;
        boolean fullHeader = !compactHeader && !imeVisible && !singleDetail;
        normalTop.setVisibility(fullHeader ? View.VISIBLE : View.GONE);
        sectionStrip.setVisibility(!compactHeader && !imeVisible ? View.VISIBLE : View.GONE);
        sectionGap.setVisibility(fullHeader ? View.VISIBLE : View.GONE);
        boolean showSearch = CATALOG.equals(section) && !singleDetail;
        if (searchRow != null) searchRow.setVisibility(showSearch ? View.VISIBLE : View.GONE);
        if (searchGap != null) searchGap.setVisibility(fullHeader && showSearch ? View.VISIBLE : View.GONE);
        if (compactSearchMenu != null) compactSearchMenu.setVisibility(compactHeader && !imeVisible ? View.VISIBLE : View.GONE);
        compactRow.setVisibility(compactHeader && !imeVisible && !showSearch ? View.VISIBLE : View.GONE);
        masterHeading.setVisibility(compactHeader || imeVisible ? View.GONE : View.VISIBLE);
        statusRetry.setVisibility(compactHeader || imeVisible ? View.GONE : View.VISIBLE);
        statusText.setMaxLines(compactHeader || imeVisible ? 1 : Integer.MAX_VALUE);
        statusText.setEllipsize(compactHeader || imeVisible ? TextUtils.TruncateAt.END : null);
        next.setVisibility(!compactHeader && !imeVisible && CATALOG.equals(section) && page != null && !empty(page.nextUrl) ? View.VISIBLE : View.GONE);
        rebuilding = false;
    }

    private void rebindBooksAfterLayout() {
        if (bookRefreshPosted || recyclerView == null || layout == null || adapter == null) return;
        bookRefreshPosted = true;
        final RecyclerView target = recyclerView;
        final GridLayoutManager manager = layout;
        final Parcelable position = manager.onSaveInstanceState();
        final int request = generation;
        target.post(() -> {
            if (recyclerView != target || root == null || adapter == null) return;
            bookRefreshPosted = false;
            adapter.notifyDataSetChanged();
            if (generation == request) manager.onRestoreInstanceState(position);
        });
    }

    private String sectionLabel() {
        return getString(LOCAL.equals(section) ? R.string.library : DOWNLOADS.equals(section) ? R.string.downloads : R.string.catalog_tab_title);
    }

    private void retryPage() {
        offline = false; preferences.edit().putBoolean("offline", false).apply();
        request(location, false);
    }

    /** Compact windows retain every navigation action without spending three fixed rows. */
    private void chooseSection() {
        ArrayList<String> labels = new ArrayList<>();
        ArrayList<Runnable> actions = new ArrayList<>();
        for (String name : new String[]{CATALOG, LOCAL, DOWNLOADS}) {
            labels.add(getString(CATALOG.equals(name) ? R.string.catalog_tab_title : LOCAL.equals(name) ? R.string.library : R.string.downloads));
            actions.add(() -> switchSection(name));
        }
        if (selected != null || !history.isEmpty()) {
            labels.add(getString(R.string.back)); actions.add(this::onBackAction);
        }
        if (CATALOG.equals(section)) {
            labels.add(getString(R.string.try_again)); actions.add(this::retryPage);
            if (page != null && !empty(page.nextUrl)) {
                labels.add(getString(R.string.next_page));
                actions.add(() -> request(new Location(page.nextUrl, location.query, false), true));
            }
        }
        labels.add(getString(R.string.server)); actions.add(this::chooseSource);
        new AlertDialog.Builder(requireContext()).setTitle(sectionLabel())
                .setItems(labels.toArray(new String[0]), (dialog, index) -> actions.get(index).run())
                .setNegativeButton(R.string.close, null).show();
    }

    private void submitSearch() {
        if (search == null) return;
        searchDraft = search.getText().toString().trim();
        InputMethodManager keyboard = (InputMethodManager) requireContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(search.getWindowToken(), 0);
        search.clearFocus();
        request(new Location(null, searchDraft, !searchDraft.isEmpty()), true);
    }

    private void request(Location target, boolean push) {
        if (push) {
            if (history.size() == 30) history.remove(0);
            history.add(location);
            selected = null; selectedFormat = null;
            savedListState = null;
        }
        location = target;
        final int id = ++generation;
        if (requestFuture != null) requestFuture.cancel(true);
        repository.cancel();
        final int viewId = viewGeneration;
        final boolean cacheOnly = offline;
        busy = true; error = null;
        updateStatus(); applyAdaptiveLayout();
        requestFuture = network.submit(() -> {
            if (id != generation || viewId != viewGeneration) return;
            CatalogPage result = null; String failure = null;
            try {
                result = target.search ? repository.search(target.query, cacheOnly) : repository.load(target.url, cacheOnly);
            } catch (Exception e) {
                failure = cacheOnly ? "cache" : "network";
            }
            final CatalogPage loaded = result;
            final String failed = failure;
            ui.post(() -> {
                if (id != generation || viewId != viewGeneration || root == null || !isAdded()) return;
                busy = false;
                error = failed;
                if (loaded != null) page = loaded;
                showSection();
            });
        });
    }

    private void switchSection(String name) {
        saveScroll();
        if (search != null) searchDraft = search.getText().toString();
        section = name; selected = null; selectedFormat = null;
        createHeader(); showSection();
    }

    private void showSection() {
        if (root == null) return;
        adapter.refresh();
        pageTitle.setText(CATALOG.equals(section) && page != null ? page.title
                : getString(LOCAL.equals(section) ? R.string.library : DOWNLOADS.equals(section) ? R.string.downloads : R.string.catalog_tab_title));
        previous.setVisibility(selected != null || !history.isEmpty() || !CATALOG.equals(section) ? View.VISIBLE : View.GONE);
        next.setVisibility(CATALOG.equals(section) && page != null && !empty(page.nextUrl) ? View.VISIBLE : View.GONE);
        next.setEnabled(!busy);
        next.setAlpha(busy ? .45f : 1f);
        updateStatus(); rebuildDetail(); applyAdaptiveLayout();
        if (savedListState != null && CATALOG.equals(section)) {
            layout.onRestoreInstanceState(savedListState); savedListState = null;
        }
    }

    private void updateStatus() {
        if (root == null || statusText == null) return;
        loading.setVisibility(busy && CATALOG.equals(section) ? View.VISIBLE : View.INVISIBLE);
        String message = null;
        if (CATALOG.equals(section)) {
            if (error != null) message = getString("cache".equals(error) ? R.string.catalog_no_cached_page : R.string.loading_error);
            else if (page != null && page.fromCache) {
                message = getString(R.string.catalog_offline);
                if (page.fetchedAt > 0) message += " · " + DateFormat.getDateFormat(requireContext()).format(new Date(page.fetchedAt));
            } else if (offline) message = getString(R.string.catalog_offline_mode);
        }
        statusBox.setVisibility(message == null ? View.GONE : View.VISIBLE);
        if (message != null) statusText.setText(message);
    }

    private void selectBook(CatalogEntry book) {
        saveScroll();
        selected = book;
        selectedFormat = book.acquisitions.isEmpty() ? null : book.acquisitions.get(0);
        rebuildDetail(); applyAdaptiveLayout();
        previous.setVisibility(View.VISIBLE);
        detailScroll.scrollTo(0, 0);
    }

    private void rebuildDetail() {
        if (detailBox == null) return;
        detailBox.removeAllViews(); detailStatus = null; detailProgress = null; primary = null;
        if (selected == null) {
            TextView heading = text(getString(R.string.catalog_no_selection), 24, ink);
            heading.setTypeface(Typeface.create("serif", Typeface.NORMAL));
            heading.setPadding(0, dp(40), 0, dp(16));
            detailBox.addView(heading, matchWrap()); return;
        }
        final CatalogEntry book = selected;
        detailBox.addView(button(getString(R.string.back), false, v -> {
            selected = null; selectedFormat = null; showSection();
        }), wrapWrap());
        FrameLayout cover = cover(book, 210);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(dp(154), dp(210));
        cp.gravity = Gravity.CENTER_HORIZONTAL; cp.topMargin = dp(12); cp.bottomMargin = dp(20);
        detailBox.addView(cover, cp);
        TextView title = text(book.title, 27, ink);
        title.setTypeface(Typeface.create("serif", Typeface.NORMAL)); title.setGravity(Gravity.CENTER);
        detailBox.addView(title, matchWrap());
        TextView author = text(book.author, 16, muted);
        author.setGravity(Gravity.CENTER); author.setPadding(0, dp(8), 0, dp(18));
        detailBox.addView(author, matchWrap());
        if (!empty(book.summary)) {
            TextView description = text(book.summary, 16, ink);
            description.setTextIsSelectable(true); description.setLineSpacing(dp(3), 1f);
            detailBox.addView(description, matchWrap()); space(detailBox, 20);
        }
        if (!book.acquisitions.isEmpty()) {
            TextView formats = text(getString(R.string.file), 14, muted);
            detailBox.addView(formats, matchWrap());
            for (CatalogAcquisition format : book.acquisitions) {
                boolean chosen = selectedFormat != null && format.url.equals(selectedFormat.url);
                Button choose = button((chosen ? "✓  " : "") + format.format, chosen, v -> {
                    selectedFormat = format;
                    int y = detailScroll.getScrollY(); rebuildDetail(); detailScroll.post(() -> detailScroll.scrollTo(0, y));
                });
                choose.setContentDescription(format.format + (chosen ? ", " + getString(R.string.selected) : ""));
                detailBox.addView(choose, matchWrap()); space(detailBox, 6);
            }
        }
        detailStatus = text("", 14, muted);
        detailStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        space(detailBox, 10); detailBox.addView(detailStatus, matchWrap());
        detailProgress = new ProgressBar(requireContext(), null, android.R.attr.progressBarStyleHorizontal);
        detailProgress.setProgressTintList(ColorStateList.valueOf(accent));
        detailBox.addView(detailProgress, new LinearLayout.LayoutParams(-1, dp(8)));
        primary = button(getString(R.string.download), true, v -> primaryAction());
        space(detailBox, 14); detailBox.addView(primary, matchWrap());
        Button folder = button(getString(R.string.folder) + " · " + folderName(), false, v -> chooseFolder());
        space(detailBox, 10); detailBox.addView(folder, matchWrap());
        refreshDetailState();
        if (savedDetailScroll > 0) {
            int y = savedDetailScroll; savedDetailScroll = 0;
            detailScroll.post(() -> detailScroll.scrollTo(0, y));
        }
    }

    private CatalogDownloads.Task currentTask() {
        if (selected == null) return null;
        if (selectedFormat == null) {
            CatalogDownloads.Task ready = completed.get(selected.id);
            if (ready != null) return ready;
        }
        for (CatalogDownloads.Task task : tasks) {
            if (selected.id.equals(task.bookId) && selectedFormat != null && selectedFormat.format.equals(task.format)
                    && !CatalogDownloads.CANCELLED.equals(task.status)) return task;
        }
        return null;
    }

    private void refreshDetailState() {
        if (primary == null || selected == null) return;
        CatalogDownloads.Task task = currentTask();
        boolean done = task != null && CatalogDownloads.COMPLETED.equals(task.status);
        boolean active = task != null && !done && !CatalogDownloads.FAILED.equals(task.status);
        primary.setEnabled(done || active || (selectedFormat != null && !offline));
        primary.setAlpha(primary.isEnabled() ? 1f : .45f);
        primary.setText(done ? getString(R.string.read_a_book) : active ? getString(R.string.downloads)
                : getString(R.string.download) + (selectedFormat == null ? "" : " " + selectedFormat.format));
        detailProgress.setVisibility(active ? View.VISIBLE : View.GONE);
        if (active) {
            detailProgress.setIndeterminate(task.total <= 0);
            if (task.total > 0) detailProgress.setProgress((int) Math.min(100, task.bytes * 100L / task.total));
        }
        detailStatus.setText(task == null ? (offline ? getString(R.string.catalog_offline_mode) : "") : taskStatus(task));
    }

    private void primaryAction() {
        CatalogDownloads.Task task = currentTask();
        if (task != null && CatalogDownloads.COMPLETED.equals(task.status)) { openTask(task); return; }
        if (task != null && !CatalogDownloads.FAILED.equals(task.status)) { switchSection(DOWNLOADS); return; }
        if (selectedFormat == null || selected == null || offline) return;
        final CatalogEntry book = selected; final CatalogAcquisition format = selectedFormat;
        final Uri folder = empty(treeUri) ? null : Uri.parse(treeUri);
        primary.setEnabled(false);
        primary.setAlpha(.45f);
        commands.execute(() -> {
            try {
                if (task != null && CatalogDownloads.FAILED.equals(task.status)) downloads.retry(task.id, folder);
                else downloads.enqueue(book, format, folder);
                ui.post(() -> { if (root != null) { Toast.makeText(requireContext(), R.string.catalog_queued, Toast.LENGTH_SHORT).show(); pollTasks(); } });
            } catch (Exception e) { ui.post(() -> showFailure(R.string.can_t_download)); }
        });
    }

    private void openTask(CatalogDownloads.Task task) {
        String path = !empty(task.localPath) ? task.localPath : task.localUri;
        if (empty(path)) { showFailure(R.string.file_not_found); return; }
        FileMeta meta = new FileMeta(path);
        meta.setTitle(task.title); meta.setAuthor(task.author);
        meta.setExt(task.format.toLowerCase(java.util.Locale.ROOT));
        try { ExtUtils.openFile(requireActivity(), meta); }
        catch (Exception e) { showFailure(R.string.book_open_failed); }
    }

    private void chooseFolder() {
        new AlertDialog.Builder(requireContext()).setTitle(R.string.folder_for_downloaded_books)
                .setItems(new String[]{getString(R.string.catalog_app_storage), getString(R.string.choose_)}, (dialog, which) -> {
                    if (which == 0) {
                        treeUri = null; preferences.edit().remove("tree").apply(); rebuildDetail();
                    } else {
                        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
                        try { startActivityForResult(intent, PICK_FOLDER); }
                        catch (Exception e) { showFailure(R.string.folder_no_access); }
                    }
                }).setNegativeButton(R.string.cancel, null).show();
    }

    @Override public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_FOLDER || resultCode != Activity.RESULT_OK || data == null || data.getData() == null) return;
        try {
            Uri tree = data.getData();
            int flags = data.getFlags() & (Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            requireContext().getContentResolver().takePersistableUriPermission(tree, flags);
            treeUri = tree.toString(); preferences.edit().putString("tree", treeUri).apply();
            rebuildDetail();
        } catch (Exception e) { showFailure(R.string.folder_no_access); }
    }

    private String folderName() {
        if (empty(treeUri)) return getString(R.string.catalog_app_storage);
        try { return DocumentsContract.getTreeDocumentId(Uri.parse(treeUri)); }
        catch (Exception e) { return getString(R.string.storage); }
    }

    private void chooseSource() {
        String chosen = repository.getMirror();
        ArrayList<String> labels = new ArrayList<>();
        labels.add(getString(R.string.automatic) + (empty(chosen) ? " ✓" : ""));
        for (String mirror : CatalogRepository.MIRRORS) {
            labels.add(Uri.parse(mirror).getHost() + (mirror.equals(chosen) ? " ✓" : ""));
        }
        labels.add(getString(R.string.catalog_offline_mode) + (offline ? " ✓" : ""));
        new AlertDialog.Builder(requireContext()).setTitle(R.string.server)
                .setItems(labels.toArray(new String[0]), (dialog, index) -> {
                    if (index == labels.size() - 1) {
                        offline = !offline; preferences.edit().putBoolean("offline", offline).apply();
                    } else {
                        repository.setMirror(index == 0 ? null : CatalogRepository.MIRRORS[index - 1]);
                        offline = false; preferences.edit().putBoolean("offline", false).apply();
                    }
                    request(location, false);
                }).setNegativeButton(R.string.close, null).show();
    }

    private final Runnable poll = this::pollTasks;
    private void pollTasks() {
        if (!resumed || !getUserVisibleHint() || root == null || polling) return;
        polling = true;
        commands.execute(() -> {
            List<CatalogDownloads.Task> snapshot;
            try { snapshot = downloads.getTasks(); } catch (Exception e) { snapshot = Collections.emptyList(); }
            final List<CatalogDownloads.Task> result = snapshot;
            ui.post(() -> {
                polling = false;
                if (root == null || !resumed) return;
                tasks = result;
                Map<String, CatalogDownloads.Task> available = new HashMap<>();
                for (CatalogDownloads.Task task : tasks) {
                    if (CatalogDownloads.COMPLETED.equals(task.status) && !available.containsKey(task.bookId)) available.put(task.bookId, task);
                }
                boolean changed = !available.keySet().equals(completed.keySet());
                completed = available;
                if (adapter != null && (changed || !CATALOG.equals(section))) {
                    Parcelable position = layout.onSaveInstanceState(); adapter.refresh(); layout.onRestoreInstanceState(position);
                }
                refreshDetailState();
                ui.removeCallbacks(poll); ui.postDelayed(poll, 1200);
            });
        });
    }

    private String taskStatus(CatalogDownloads.Task task) {
        if (CatalogDownloads.COMPLETED.equals(task.status)) return getString(R.string.catalog_on_device);
        if (CatalogDownloads.FAILED.equals(task.status)) return empty(task.error) ? getString(R.string.can_t_download) : task.error;
        if (CatalogDownloads.WAITING_NETWORK.equals(task.status)) return getString(R.string.catalog_waiting_network);
        if (CatalogDownloads.CANCELLED.equals(task.status)) return getString(R.string.cancel);
        if (CatalogDownloads.QUEUED.equals(task.status)) return getString(R.string.catalog_queued);
        String value = Formatter.formatShortFileSize(requireContext(), task.bytes);
        if (task.total > 0) value += " / " + Formatter.formatShortFileSize(requireContext(), task.total);
        return getString(R.string.download) + " · " + value;
    }

    private void taskAction(CatalogDownloads.Task task, boolean retry) {
        if (retry && offline) { showFailure(R.string.catalog_offline_mode); return; }
        final Uri destination = empty(treeUri) ? null : Uri.parse(treeUri);
        commands.execute(() -> {
            try { if (retry) downloads.retry(task.id, destination); else downloads.cancel(task.id); }
            catch (Exception e) { ui.post(() -> showFailure(R.string.can_t_download)); }
            ui.post(this::pollTasks);
        });
    }

    private void showFailure(int resource) {
        if (root == null || !isAdded()) return;
        Toast.makeText(requireContext(), resource, Toast.LENGTH_LONG).show(); refreshDetailState();
    }

    private void saveScroll() {
        if (layout != null && CATALOG.equals(section)) savedListState = layout.onSaveInstanceState();
    }

    @Override public boolean isBackPressed() { return onBackAction(); }
    public boolean onBackAction() {
        if (selected != null) { selected = null; selectedFormat = null; showSection(); return true; }
        if (!CATALOG.equals(section)) { switchSection(CATALOG); return true; }
        if (!history.isEmpty()) {
            Location previous = history.remove(history.size() - 1);
            searchDraft = previous.query;
            if (search != null) search.setText(searchDraft);
            request(previous, false); return true;
        }
        return false;
    }

    @Override public void onSaveInstanceState(@NonNull Bundle out) {
        super.onSaveInstanceState(out);
        if (search != null) searchDraft = search.getText().toString();
        saveScroll();
        out.putString("section", section); out.putString("draft", searchDraft);
        out.putSerializable("location", location); out.putSerializable("history", history);
        out.putSerializable("selected", selected); out.putSerializable("format", selectedFormat);
        out.putParcelable("list", savedListState);
        out.putInt("detailScroll", detailScroll == null ? 0 : detailScroll.getScrollY());
    }

    @Override public void onResume() {
        super.onResume(); resumed = true; updateStatusIcons(getUserVisibleHint()); pollTasks();
    }
    @Override public void onPause() {
        resumed = false; updateStatusIcons(false); ui.removeCallbacks(poll); super.onPause();
    }
    @Override public void setUserVisibleHint(boolean visible) {
        super.setUserVisibleHint(visible);
        updateStatusIcons(visible && resumed);
        if (visible) pollTasks(); else ui.removeCallbacks(poll);
    }
    @Override public void onDestroyView() {
        updateStatusIcons(false);
        saveScroll();
        if (search != null) searchDraft = search.getText().toString();
        if (detailScroll != null) savedDetailScroll = detailScroll.getScrollY();
        viewGeneration++; generation++; busy = false;
        if (requestFuture != null) requestFuture.cancel(true);
        repository.cancel();
        ui.removeCallbacks(poll);
        root = null; header = null; body = null; detailBox = null; detailScroll = null;
        if (recyclerView != null) recyclerView.setAdapter(null);
        recyclerView = null; adapter = null; layout = null; bookRefreshPosted = false; bookLayoutWidth = 0;
        super.onDestroyView();
    }
    @Override public void onDestroy() {
        repository.cancel();
        network.shutdownNow(); commands.shutdownNow(); ui.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
    @Override public void notifyFragment() { if (root != null) { pollTasks(); refreshDetailState(); } }
    @Override public void resetFragment() { onTintChanged(); }
    @Override public void onTintChanged() {
        if (root == null) return;
        saveScroll(); if (search != null) searchDraft = search.getText().toString();
        if (detailScroll != null) savedDetailScroll = detailScroll.getScrollY();
        palette(); root.setBackgroundColor(paper); createHeader(); createBody(); showSection();
    }
    @Override public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration); applyAdaptiveLayout();
    }

    private class ShelfAdapter extends RecyclerView.Adapter<ShelfHolder> {
        private final List<CatalogEntry> entries = new ArrayList<>();
        private final List<CatalogDownloads.Task> downloadRows = new ArrayList<>();
        void refresh() {
            entries.clear(); downloadRows.clear();
            if (CATALOG.equals(section)) { if (page != null) entries.addAll(page.entries); }
            else if (LOCAL.equals(section)) {
                Set<String> shown = new HashSet<>();
                for (CatalogDownloads.Task task : tasks) {
                    if (!CatalogDownloads.COMPLETED.equals(task.status) || !shown.add(task.bookId)) continue;
                    CatalogEntry entry = new CatalogEntry(); entry.id = task.bookId; entry.title = task.title; entry.author = task.author;
                    entries.add(entry);
                }
            } else downloadRows.addAll(tasks);
            notifyDataSetChanged();
        }
        boolean isBook(int position) {
            return !DOWNLOADS.equals(section) && position < entries.size()
                    && (!entries.get(position).acquisitions.isEmpty() || LOCAL.equals(section));
        }
        @Override public int getItemCount() {
            int count = DOWNLOADS.equals(section) ? downloadRows.size() : entries.size();
            return Math.max(1, count);
        }
        @Override public int getItemViewType(int position) {
            if (DOWNLOADS.equals(section)) return downloadRows.isEmpty() ? 3 : 2;
            return entries.isEmpty() ? 3 : isBook(position) ? 0 : 1;
        }
        @NonNull @Override public ShelfHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            LinearLayout container = column();
            container.setPadding(dp(7), dp(10), dp(7), dp(14));
            RecyclerView.LayoutParams itemParams = new RecyclerView.LayoutParams(-1, -2);
            if (type == 2) itemParams.bottomMargin = dp(12);
            container.setLayoutParams(itemParams);
            return new ShelfHolder(container);
        }
        @Override public void onBindViewHolder(@NonNull ShelfHolder holder, int position) {
            LinearLayout card = holder.container;
            card.removeAllViews(); card.setBackgroundColor(Color.TRANSPARENT);
            card.setOnClickListener(null); card.setFocusable(false); card.setClickable(false);
            if (getItemViewType(position) == 3) {
                TextView empty = text(getString(busy && CATALOG.equals(section) ? R.string.please_wait : R.string.empy), 18, muted);
                empty.setGravity(Gravity.CENTER); empty.setPadding(dp(16), dp(40), dp(16), dp(40));
                card.addView(empty, matchWrap()); return;
            }
            if (getItemViewType(position) == 2) { bindTask(card, downloadRows.get(position)); return; }
            CatalogEntry entry = entries.get(position);
            if (isBook(position)) {
                LinearLayout labels = card;
                if (compactBooks) {
                    // A short landscape window should show identifiable books, not
                    // only the top half of a full-height jacket.
                    LinearLayout compact = row(); compact.setGravity(Gravity.TOP);
                    LinearLayout.LayoutParams coverParams = new LinearLayout.LayoutParams(dp(60), dp(84));
                    coverParams.setMarginEnd(dp(12));
                    compact.addView(cover(entry, 84), coverParams);
                    labels = column();
                    compact.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
                    card.addView(compact, matchWrap());
                } else {
                    // Book jackets keep their proportions in portrait and on tablets.
                    int cellWidth = recyclerView.getWidth() / Math.max(1, layout.getSpanCount()) - dp(14);
                    int jacketWidth = Math.min(dp(156), Math.max(dp(110), cellWidth));
                    int jacketHeight = Math.round(jacketWidth * 1.4f);
                    FrameLayout jacketHolder = new FrameLayout(requireContext());
                    FrameLayout.LayoutParams jacketParams = new FrameLayout.LayoutParams(jacketWidth, jacketHeight);
                    jacketParams.gravity = Gravity.CENTER_HORIZONTAL;
                    jacketHolder.addView(cover(entry, 178), jacketParams);
                    card.addView(jacketHolder, new LinearLayout.LayoutParams(-1, jacketHeight));
                }
                TextView title = text(entry.title, 16, ink);
                title.setTypeface(Typeface.DEFAULT, Typeface.BOLD); title.setPadding(0, compactBooks ? 0 : dp(12), 0, dp(4));
                title.setMaxLines(compactBooks ? 2 : 3); title.setEllipsize(TextUtils.TruncateAt.END);
                labels.addView(title, matchWrap());
                TextView author = text(entry.author, 14, muted);
                author.setMaxLines(2); author.setEllipsize(TextUtils.TruncateAt.END);
                labels.addView(author, matchWrap());
                if (completed.containsKey(entry.id)) {
                    TextView saved = text(getString(R.string.catalog_on_device), 12, accent);
                    saved.setPadding(0, dp(6), 0, 0); labels.addView(saved, matchWrap());
                }
                card.setContentDescription(entry.title + ", " + entry.author
                        + (completed.containsKey(entry.id) ? ", " + getString(R.string.catalog_on_device) : ""));
                card.setFocusable(true); card.setClickable(true);
                card.setOnClickListener(v -> selectBook(entry));
            } else {
                Button navigation = button(entry.title + "  ›", false, v -> {
                    if (!empty(entry.navigationUrl)) request(new Location(entry.navigationUrl, location.query, false), true);
                });
                navigation.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                navigation.setPadding(dp(18), dp(16), dp(18), dp(16));
                card.addView(navigation, matchWrap());
                if (!empty(entry.summary)) card.addView(text(entry.summary, 14, muted), matchWrap());
            }
        }
        private void bindTask(LinearLayout box, CatalogDownloads.Task task) {
            box.setPadding(dp(16), dp(14), dp(16), dp(14)); box.setBackground(shape(surface, 14, line));
            TextView title = text(task.title, 18, ink); title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            box.addView(title, matchWrap());
            box.addView(text(task.author, 14, muted), matchWrap());
            TextView status = text(task.format + " · " + taskStatus(task), 14, muted);
            status.setPadding(0, dp(8), 0, dp(10)); box.addView(status, matchWrap());
            boolean done = CatalogDownloads.COMPLETED.equals(task.status);
            boolean failed = CatalogDownloads.FAILED.equals(task.status) || CatalogDownloads.CANCELLED.equals(task.status);
            if (!done && !failed) {
                ProgressBar progress = new ProgressBar(requireContext(), null, android.R.attr.progressBarStyleHorizontal);
                progress.setProgressTintList(ColorStateList.valueOf(accent)); progress.setIndeterminate(task.total <= 0);
                if (task.total > 0) progress.setProgress((int) Math.min(100, task.bytes * 100L / task.total));
                box.addView(progress, new LinearLayout.LayoutParams(-1, dp(6)));
                space(box, 12);
            }
            Button action = button(getString(done ? R.string.read_a_book : failed ? R.string.try_again : R.string.cancel), done, v -> {
                if (done) openTask(task); else taskAction(task, failed);
            });
            action.setEnabled(!failed || !offline);
            action.setAlpha(action.isEnabled() ? 1f : .45f);
            box.addView(action, matchWrap());
            if (failed) {
                space(box, 8);
                box.addView(button(getString(R.string.folder), false, v -> chooseFolder()), matchWrap());
            }
        }
    }

    private static class ShelfHolder extends RecyclerView.ViewHolder {
        final LinearLayout container;
        ShelfHolder(LinearLayout view) { super(view); container = view; }
    }

    private FrameLayout cover(CatalogEntry book, int height) {
        FrameLayout frame = new FrameLayout(requireContext());
        int[] colors = {Color.rgb(41, 73, 56), Color.rgb(78, 93, 88), Color.rgb(112, 77, 57), Color.rgb(68, 79, 105)};
        int color = colors[(book.title == null ? 0 : (book.title.hashCode() & Integer.MAX_VALUE)) % colors.length];
        frame.setBackground(shape(color, 9, color)); frame.setClipToOutline(true);
        final View fallback;
        if (height < 100) {
            String title = book.title == null ? "" : book.title.trim();
            int initial = title.codePoints().filter(Character::isLetterOrDigit).findFirst().orElse('?');
            TextView monogram = text(new String(Character.toChars(initial)).toUpperCase(java.util.Locale.ROOT), 28,
                    Color.rgb(249, 244, 223));
            monogram.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 28);
            monogram.setTypeface(Typeface.create("serif", Typeface.NORMAL)); monogram.setGravity(Gravity.CENTER);
            fallback = monogram;
        } else {
            LinearLayout jacket = column(); jacket.setPadding(dp(16), dp(18), dp(14), dp(14));
            TextView author = text(book.author, 12, Color.rgb(233, 231, 214));
            // This is decorative jacket artwork, like a downloaded cover image. Full
            // accessible title/author below the jacket keep the user's font scale.
            author.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
            author.setMaxLines(2); author.setEllipsize(TextUtils.TruncateAt.END);
            jacket.addView(author, matchWrap());
            String jacketTitle = book.title == null ? "" : book.title.split(":", 2)[0];
            TextView title = text(jacketTitle, 22, Color.rgb(249, 244, 223));
            title.setTypeface(Typeface.create("serif", Typeface.NORMAL));
            title.setMaxLines(5); title.setEllipsize(TextUtils.TruncateAt.END);
            title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 22);
            if (Build.VERSION.SDK_INT >= 23) {
                title.setBreakStrategy(android.text.Layout.BREAK_STRATEGY_SIMPLE);
                title.setHyphenationFrequency(android.text.Layout.HYPHENATION_FREQUENCY_NONE);
            }
            final int[] jacketTypeSize = {22};
            title.addOnLayoutChangeListener((view, l, t, r, b, ol, ot, or, ob) -> {
                if (r <= l) return;
                Paint measure = new Paint(title.getPaint()); measure.setTextSize(dp(22));
                float longest = 0;
                for (String word : jacketTitle.split("\\s+")) longest = Math.max(longest, measure.measureText(word));
                int width = r - l - title.getPaddingLeft() - title.getPaddingRight();
                int maximum = Math.max(14, Math.min(22, (int) Math.floor(22f * Math.max(1, width - dp(2)) / Math.max(1, longest))));
                if (jacketTypeSize[0] == maximum) return;
                jacketTypeSize[0] = maximum;
                title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, maximum);
            });
            title.setPadding(0, dp(14), 0, 0);
            jacket.addView(title, new LinearLayout.LayoutParams(-1, 0, 1));
            fallback = jacket;
        }
        frame.addView(fallback, new FrameLayout.LayoutParams(-1, -1));
        if (!empty(book.coverUrl)) {
            ImageView image = new ImageView(requireContext());
            image.setScaleType(ImageView.ScaleType.FIT_CENTER); image.setBackgroundColor(Color.TRANSPARENT);
            frame.addView(image, new FrameLayout.LayoutParams(-1, -1));
            Glide.with(this).load(new CatalogCover(book.coverUrl)).onlyRetrieveFromCache(offline).listener(new RequestListener<Drawable>() {
                @Override public boolean onLoadFailed(GlideException e, Object model,
                        Target<Drawable> target, boolean first) { return false; }
                @Override public boolean onResourceReady(Drawable resource, Object model,
                        Target<Drawable> target, DataSource source, boolean first) {
                    fallback.setVisibility(View.INVISIBLE);
                    return false;
                }
            }).into(image);
        }
        frame.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        return frame;
    }

    private LinearLayout column() {
        LinearLayout view = new LinearLayout(requireContext()); view.setOrientation(LinearLayout.VERTICAL); return view;
    }
    private LinearLayout row() {
        LinearLayout view = new LinearLayout(requireContext()); view.setOrientation(LinearLayout.HORIZONTAL);
        view.setGravity(Gravity.CENTER_VERTICAL); return view;
    }
    private TextView text(String content, int size, int color) {
        TextView view = new TextView(requireContext()); view.setText(content == null ? "" : content);
        view.setTextSize(size); view.setTextColor(color); view.setIncludeFontPadding(true); return view;
    }
    private Button button(String label, boolean filled, View.OnClickListener action) {
        Button button = new Button(requireContext()); button.setText(label); button.setAllCaps(false);
        button.setTextSize(14); button.setTextColor(filled ? onAccent : ink);
        button.setMinHeight(dp(48)); button.setMinimumHeight(dp(48)); button.setMinimumWidth(dp(48));
        button.setPadding(dp(12), dp(10), dp(12), dp(10));
        button.setStateListAnimator(null); button.setElevation(0);
        int ripple = ((filled ? onAccent : accent) & 0x00FFFFFF) | 0x22000000;
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(ripple),
                shape(filled ? accent : surface, 12, filled ? accent : line), null));
        button.setOnClickListener(action); return button;
    }
    private GradientDrawable shape(int color, int radius, int border) {
        GradientDrawable shape = new GradientDrawable(); shape.setColor(color); shape.setCornerRadius(dp(radius)); shape.setStroke(dp(1), border); return shape;
    }
    private void space(LinearLayout parent, int height) { parent.addView(new View(requireContext()), new LinearLayout.LayoutParams(1, dp(height))); }
    private float density() { return getResources().getDisplayMetrics().density; }
    private int dp(float value) { return Math.round(value * density()); }
    private static boolean empty(String value) { return value == null || value.trim().isEmpty(); }
    private LinearLayout.LayoutParams matchWrap() { return new LinearLayout.LayoutParams(-1, -2); }
    private LinearLayout.LayoutParams wrapWrap() { return new LinearLayout.LayoutParams(-2, -2); }
}
