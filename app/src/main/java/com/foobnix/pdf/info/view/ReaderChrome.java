package com.foobnix.pdf.info.view;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.cardview.widget.CardView;
import androidx.core.view.WindowCompat;
import androidx.core.widget.TextViewCompat;

import com.foobnix.pdf.info.R;
import com.foobnix.pdf.info.TintUtil;
import com.foobnix.model.AppState;
import com.foobnix.pdf.info.wrapper.DocumentController;
import com.foobnix.ui2.LibraryDesign;

/** Presentation only: existing actions, reading modes and document preferences stay in place. */
public final class ReaderChrome {
    private ReaderChrome() { }

    public static void apply(Activity activity) {
        if (activity == null) return;
        apply(activity.getWindow().getDecorView());
        Palette palette = new Palette(true);
        activity.getWindow().setNavigationBarColor(palette.paper);
        WindowCompat.getInsetsController(activity.getWindow(), activity.getWindow().getDecorView())
                .setAppearanceLightStatusBars(!palette.dark);
        WindowCompat.getInsetsController(activity.getWindow(), activity.getWindow().getDecorView())
                .setAppearanceLightNavigationBars(!palette.dark);
    }

    /** Also used to verify both native reader layouts without opening or modifying a book. */
    public static void apply(View root) {
        if (root == null) return;
        Palette palette = new Palette(true);
        for (int id : new int[]{R.id.menuLayout, R.id.actionBar, R.id.bottomBar}) {
            View panel = root.findViewById(id);
            if (panel == null) continue;
            panel.setBackground(shape(panel,palette.paper,16,true,palette));
            panel.setElevation(dp(panel,2));
            tintControls(panel, true, palette);
        }
        View outer = root.findViewById(R.id.bottomBar1);
        if (outer != null) outer.setBackgroundColor(palette.paper);
        View title = root.findViewById(R.id.document_title_bar);
        if (title != null) {
            title.setBackgroundColor(palette.paper);
            tintControls(title, false, palette);
            View titleBar = root.findViewById(R.id.titleBar);
            if (titleBar != null) titleBar.setBackgroundColor(palette.paper);
            View logo = root.findViewById(R.id.lirbiLogo);
            if (logo != null) logo.setBackgroundColor(palette.paper);
        }
        for (int id : new int[]{R.id.title, R.id.bookName}) {
            TextView name = root.findViewById(id);
            if (name != null) {
                name.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
                name.setEllipsize(TextUtils.TruncateAt.END);
            }
        }
        TextView mode = root.findViewById(R.id.modeName);
        if (mode != null) {
            mode.setTextColor(palette.accent);
            mode.setMaxLines(2); mode.setEllipsize(TextUtils.TruncateAt.END);
            mode.setGravity(Gravity.CENTER);
            mode.setPadding(dp(mode, 8), dp(mode, 6), dp(mode, 8), dp(mode, 6));
        }
        View status = root.findViewById(R.id.systemBarHack);
        if (status != null) status.setBackgroundColor(palette.paper);
    }


    /** The same resizable surface hosts settings, contents, bookmarks and search. */
    public static void popup(View popup) {
        Context context = popup.getContext();
        while (context instanceof ContextWrapper && !(context instanceof Activity)) context = ((ContextWrapper) context).getBaseContext();
        Palette palette = new Palette(context instanceof Activity && DocumentController.isReader((Activity) context));
        if (popup instanceof CardView) {
            CardView card = (CardView) popup;
            card.setCardBackgroundColor(palette.paper);
            card.setRadius(dp(card,16)); card.setCardElevation(dp(card,3));
        }
        View header = popup.findViewById(R.id.topLayout);
        if (header != null) {
            header.setBackgroundColor(palette.surface);
            header.setPadding(dp(header, 8), dp(header, 6), dp(header, 8), dp(header, 6));
            tintControls(header, true, palette);
        }
        TextView title = popup.findViewById(R.id.dialogTitle);
        if (title != null) {
            title.setAllCaps(false); title.setSingleLine(false); title.setMaxLines(2);
            title.setTextSize(20); title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            title.setEllipsize(TextUtils.TruncateAt.END);
        }
        TextView back = popup.findViewById(R.id.onTitleAction);
        if (back != null) {
            back.setAllCaps(false);
            back.setMaxLines(2); back.setEllipsize(TextUtils.TruncateAt.END);
        }
        View content = popup.findViewById(R.id.popupContent);
        if (content != null) {
            content.setBackgroundColor(palette.paper);
            content.setPadding(dp(content, 12), dp(content, 10), dp(content, 12), dp(content, 28));
            settings(content, palette);
        }
        for (int id : new int[]{R.id.left, R.id.rigth}) {
            View handle = popup.findViewById(id);
            if (handle != null && handle.getBackground() != null)
                handle.setBackgroundTintList(ColorStateList.valueOf(palette.muted));
        }
    }

    private static void tintControls(View view, boolean spacious, Palette palette) {
        if ("no_tint".equals(view.getTag())) return;
        if (view instanceof TextView) {
            TextView text = (TextView) view;
            text.setTextColor(palette.ink);
            if (view.getId()==R.id.playListName || view.getId()==R.id.playListNameEdit) {
                text.setTextColor(palette.accent);
                TextViewCompat.setCompoundDrawableTintList(text,ColorStateList.valueOf(palette.accent));
                text.setTextSize(14);text.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
                text.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22224E3B),
                        shape(view,palette.surface,10,false,palette),null));
            }
            if (spacious && view.isClickable()) touchTarget(view, false);
        }
        if (view instanceof ImageView) {
            ImageView image = (ImageView) view;
            image.setColorFilter(palette.ink);
            image.setImageTintList(ColorStateList.valueOf(palette.ink));
            if (spacious && (view.isClickable() || view.getContentDescription() != null)) {
                touchTarget(view, true);
                view.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22224E3B),
                        shape(view,palette.surface,10,false,palette),null));
                view.setPadding(dp(view, 12), dp(view, 12), dp(view, 12), dp(view, 12));
                if (view.getLayoutParams() instanceof LinearLayout.LayoutParams) {
                    LinearLayout.LayoutParams p = (LinearLayout.LayoutParams) view.getLayoutParams();
                    p.setMarginStart(dp(view, 3)); p.setMarginEnd(dp(view, 3));
                    view.setLayoutParams(p);
                }
            }
        }
        if (view instanceof SeekBar) {
            SeekBar seek = (SeekBar) view;
            seek.setProgressTintList(ColorStateList.valueOf(palette.accent));
            seek.setThumbTintList(ColorStateList.valueOf(palette.accent));
            seek.setProgressBackgroundTintList(ColorStateList.valueOf(palette.line));
            seek.setMinimumHeight(dp(seek, 48));
        }
        if (view instanceof ViewGroup) {
            ViewGroup parent = (ViewGroup) view;
            for (int i = 0; i < parent.getChildCount(); i++) tintControls(parent.getChildAt(i), spacious, palette);
        }
    }

    private static void settings(View view, Palette palette) {
        // Colour previews are document settings, not chrome. Never recolour their samples.
        if ("no_tint".equals(view.getTag()) || view instanceof CustomColorView) return;
        if (view instanceof TextView && (view.getId() == R.id.onDayColor || view.getId() == R.id.onNigthColor)) {
            // DragingDialogs already assigns the preset's text colour (black/white
            // for its day/night textures). The general link accent would erase it.
            ((TextView) view).setMinHeight(dp(view, 48));
            return;
        }
        if (view instanceof CustomSeek) arrangeSeek((CustomSeek) view, palette);
        if (view instanceof CompoundButton) {
            CompoundButton button = (CompoundButton) view;
            button.setButtonTintList(new ColorStateList(new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                    new int[]{palette.accent, palette.muted}));
            button.setTextColor(palette.ink);
            button.setMinHeight(dp(button, 48));
            button.setPadding(button.getPaddingLeft(), dp(button, 6), button.getPaddingRight(), dp(button, 6));
        } else if (view instanceof TextView && (view instanceof Button || view.isClickable())) {
            TextView text = (TextView) view;
            text.setTextColor(palette.accent); text.setMinHeight(dp(text, 48));
            if (view instanceof Button) {
                text.setAllCaps(false);
                text.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22224E3B),
                        shape(view, palette.surface, 14, true, palette), null));
            }
        }
        if (view instanceof SeekBar) tintControls(view, false, palette);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) settings(group.getChildAt(i), palette);
        }
    }

    /** Labels use their own line so long translations and 200% text cannot squeeze the slider. */
    private static void arrangeSeek(CustomSeek control, Palette palette) {
        View title = control.findViewById(R.id.name);
        View value = control.findViewById(R.id.value);
        View slider = control.findViewById(R.id.seek);
        if (title == null || slider == null || title.getParent() != slider.getParent()) return;
        LinearLayout row = (LinearLayout) slider.getParent();
        if (row.getOrientation() != LinearLayout.HORIZONTAL) return;
        LinearLayout column = new LinearLayout(control.getContext()); column.setOrientation(LinearLayout.VERTICAL);
        LinearLayout heading = new LinearLayout(control.getContext()); heading.setGravity(Gravity.CENTER_VERTICAL);
        row.removeView(title); row.removeView(value);
        ((TextView) title).setSingleLine(false); ((TextView) title).setMaxLines(3);
        heading.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        ((TextView) value).setEms(0); ((TextView) value).setMinEms(0); ((TextView) value).setMaxEms(Integer.MAX_VALUE);
        value.setMinimumWidth(dp(value, 48));
        heading.addView(value, new LinearLayout.LayoutParams(-2, -2));
        ViewGroup parent = (ViewGroup) row.getParent(); parent.removeView(row);
        column.addView(heading, new LinearLayout.LayoutParams(-1, -2));
        column.addView(row, new LinearLayout.LayoutParams(-1, -2));
        parent.addView(column, new ViewGroup.LayoutParams(-1, -2));
        slider.setLayoutParams(new LinearLayout.LayoutParams(0, dp(slider, 48), 1));
        for (int id : new int[]{R.id.minus, R.id.plus}) {
            View action = control.findViewById(id);
            touchTarget(action, true); tintControls(action, true, palette);
        }
        column.setPadding(0, dp(column, 4), 0, dp(column, 8));
    }

    private static void touchTarget(View view, boolean square) {
        if (view == null) return;
        view.setMinimumHeight(dp(view, 48)); view.setMinimumWidth(dp(view, 48));
        ViewGroup.LayoutParams p = view.getLayoutParams();
        if (p != null) {
            if (p.height > 0) p.height = Math.max(p.height, dp(view, 48));
            if (square && p.width > 0) p.width = Math.max(p.width, dp(view, 48));
            view.setLayoutParams(p);
        }
    }

    private static GradientDrawable shape(View view, int color, int radius, boolean border, Palette palette) {
        GradientDrawable result = new GradientDrawable(); result.setColor(color);
        result.setCornerRadius(dp(view, radius));
        if (border) result.setStroke(dp(view, 1), palette.line);
        return result;
    }


    /** Reader day/night is independent of the library theme and must remain so. */
    public static int paper() { return new Palette(true).paper; }
    public static boolean dark() { return !AppState.get().isDayNotInvert; }

    private static final class Palette {
        final boolean dark;
        final int paper, surface, ink, muted, accent, line;
        Palette(boolean reader) {
            dark = reader ? ReaderChrome.dark() : LibraryDesign.dark();
            if (!reader) {
                paper = LibraryDesign.paper(); surface = LibraryDesign.surface(); ink = LibraryDesign.ink();
                muted = LibraryDesign.muted(); accent = LibraryDesign.accent(); line = LibraryDesign.line();
            } else {
                boolean oled = dark && AppState.get().appTheme == AppState.THEME_DARK_OLED;
                boolean eink = AppState.get().appTheme == AppState.THEME_INK;
                paper = oled ? Color.BLACK : Color.parseColor(dark ? "#12191D" : "#F5F7F8");
                surface = oled ? Color.BLACK : Color.parseColor(dark ? "#1D272D" : "#FFFFFF");
                ink = eink ? Color.BLACK : Color.parseColor(dark ? "#E7EEF0" : "#17272E");
                muted = eink ? Color.BLACK : Color.parseColor(dark ? "#ADBCC2" : "#596970");
                int stock = Color.parseColor(AppState.STYLE_COLORS.get(AppState.DEFAULT_STYLE_COLOR));
                accent = eink ? Color.BLACK : AppState.get().tintThemeColor != stock ? TintUtil.getColorInDayNighth()
                        : Color.parseColor(dark ? "#B5D0BA" : "#244C3A");
                line = Color.parseColor(dark ? "#34434A" : "#DDE5E8");
            }
        }
    }

    private static int dp(View view, int value) {
        return Math.round(view.getResources().getDisplayMetrics().density * value);
    }
}
