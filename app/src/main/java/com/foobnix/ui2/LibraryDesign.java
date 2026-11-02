package com.foobnix.ui2;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.cardview.widget.CardView;
import androidx.recyclerview.widget.RecyclerView;

import com.foobnix.model.AppState;
import com.foobnix.pdf.info.R;
import com.foobnix.pdf.info.TintUtil;

/** Shared bookshelf surfaces. Styling never replaces an action, listener, model or preference. */
public final class LibraryDesign {
    private LibraryDesign() {}
    public static boolean dark() { return AppState.get().appTheme == AppState.THEME_DARK || AppState.get().appTheme == AppState.THEME_DARK_OLED; }
    public static int paper() { return AppState.get().appTheme == AppState.THEME_DARK_OLED ? Color.BLACK : Color.parseColor(dark() ? "#12191D" : "#F5F7F8"); }
    public static int surface() { return AppState.get().appTheme == AppState.THEME_DARK_OLED ? Color.BLACK : Color.parseColor(dark() ? "#1D272D" : "#FFFFFF"); }
    public static int ink() { return AppState.get().appTheme == AppState.THEME_INK ? Color.BLACK : Color.parseColor(dark() ? "#E7EEF0" : "#17272E"); }
    public static int muted() { return AppState.get().appTheme == AppState.THEME_INK ? Color.BLACK : Color.parseColor(dark() ? "#ADBCC2" : "#596970"); }
    public static int accent() {
        if (AppState.get().appTheme == AppState.THEME_INK) return Color.BLACK;
        int original = Color.parseColor(AppState.STYLE_COLORS.get(AppState.DEFAULT_STYLE_COLOR));
        // Keep the user's custom theme colour; the stock indigo adopts the bookshelf green.
        if (AppState.get().tintThemeColor != original) return TintUtil.getColorInDayNighth();
        return Color.parseColor(dark() ? "#B5D0BA" : "#244C3A");
    }
    public static int onAccent() { int c=accent(); return .2126*Color.red(c)+.7152*Color.green(c)+.0722*Color.blue(c) > 150 ? Color.BLACK : Color.WHITE; }
    public static int line() { return Color.parseColor(dark() ? "#34434A" : "#DDE5E8"); }
    public static int dp(View view, int value) { return Math.round(value * view.getResources().getDisplayMetrics().density); }
    public static GradientDrawable shape(View view, int fill, int radius, boolean border) {
        GradientDrawable drawable = new GradientDrawable(); drawable.setColor(fill);
        drawable.setCornerRadius(dp(view, radius)); if (border) drawable.setStroke(dp(view, 1), line());
        return drawable;
    }
    public static void action(View view) {
        if (view == null) return;
        view.setMinimumHeight(dp(view, 48)); view.setMinimumWidth(dp(view, 48));
        ViewGroup.LayoutParams lp = view.getLayoutParams();
        if (lp != null) {
            if (lp.width > 0 && lp.width < dp(view, 48)) lp.width = dp(view, 48);
            if (view instanceof ImageView) { lp.width = dp(view, 48); lp.height = dp(view, 48); }
            else if (lp.height > 0 && lp.height < dp(view, 48)) lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            if (lp instanceof LinearLayout.LayoutParams && view.getParent() instanceof LinearLayout) {
                ((LinearLayout.LayoutParams)lp).setMarginEnd(dp(view,4));
            }
            view.setLayoutParams(lp);
        }
        if (view instanceof ImageView) {
            ImageView image=(ImageView)view;
            image.clearColorFilter();
            if(image.getDrawable()!=null)image.getDrawable().mutate().clearColorFilter();
            image.setImageTintList(ColorStateList.valueOf(accent()));
            view.setPadding(dp(view, 12), dp(view, 12), dp(view, 12), dp(view, 12));
        } else if (view instanceof TextView) {
            TextView text = (TextView) view;
            text.setTextColor(ink()); text.setAllCaps(false); text.setGravity(Gravity.CENTER_VERTICAL);text.setTextSize(14);
        }
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(dark() ? 0x28FFFFFF : 0x18000000),
                shape(view, surface(), 10, false), shape(view, Color.WHITE, 10, false)));
    }
    public static void paintHeader(View root) {
        if (root == null) return;
        root.setBackgroundColor(paper());
        if (root instanceof ViewGroup) {
            for (int i=0;i<((ViewGroup) root).getChildCount();i++) paintHeaderChild(((ViewGroup) root).getChildAt(i));
        }
    }
    private static void paintHeaderChild(View view) {
        if (view instanceof EditText) {
            EditText text=(EditText)view; text.setTextColor(ink()); text.setHintTextColor(muted()); text.setTextSize(16);
            text.setMinimumHeight(dp(view,48)); text.setBackground(shape(view,surface(),12,false));
            text.setPadding(dp(view,14),dp(view,10),Math.max(view.getPaddingRight(),dp(view,14)),dp(view,10));
        } else if (view instanceof ImageView && view.getId()!=R.id.browserItemIcon) action(view);
        else if (view instanceof TextView) {
            TextView text=(TextView)view; text.setAllCaps(false); text.setTextColor(ink());
            if (view.isClickable()) action(view);
            else if (isHeading(view.getId())) {
                text.setTypeface(Typeface.DEFAULT,Typeface.BOLD); text.setTextSize(20);
                text.setSingleLine(true); text.setEllipsize(TextUtils.TruncateAt.END);
                text.setMinimumHeight(dp(view,48));
                ViewGroup.LayoutParams lp=view.getLayoutParams(); lp.height=ViewGroup.LayoutParams.WRAP_CONTENT; view.setLayoutParams(lp);
            }
        }
        if (view instanceof ViewGroup) {
            if (!(view instanceof CardView)) view.setBackgroundColor(Color.TRANSPARENT);
            for (int i=0;i<((ViewGroup)view).getChildCount();i++) paintHeaderChild(((ViewGroup)view).getChildAt(i));
            if (view.getId()==R.id.secondTopPanel) {
                ViewGroup.LayoutParams lp=view.getLayoutParams(); lp.height=ViewGroup.LayoutParams.WRAP_CONTENT; view.setLayoutParams(lp);
            }
        }
    }
    private static boolean isHeading(int id) { return id==R.id.recentName || id==R.id.favoritesName || id==R.id.titleView || id==R.id.syncHeader || id==R.id.name; }
    public static void prepareTab(View root) {
        root.setBackgroundColor(paper());
        if (root instanceof LinearLayout && ((LinearLayout)root).getChildCount()>0) {
            View header=((LinearLayout)root).getChildAt(0);
            if (header instanceof LibraryHeader) {
                header.setPadding(dp(header,12),dp(header,6),dp(header,12),dp(header,6));
                ViewGroup.LayoutParams lp=header.getLayoutParams(); lp.height=ViewGroup.LayoutParams.WRAP_CONTENT; header.setLayoutParams(lp);
            }
        }
        paintTab(root);
    }
    public static void paintTab(View root) {
        root.setBackgroundColor(paper());
        for (int id : new int[]{R.id.filterLayout,R.id.panelRecent,R.id.pathContainer,R.id.topPanel}) paintHeader(root.findViewById(id));
        for (int id : new int[]{R.id.layoutOnGrant,R.id.layoutError}) {
            View notice=root.findViewById(id); if (notice!=null) { paintHeader(notice); notice.setPadding(dp(root,16),dp(root,8),dp(root,16),dp(root,8)); }
        }
        TextView permission=root.findViewById(R.id.onGrantPermissionText);
        if (permission!=null) {
            permission.setTextSize(13);
        }
        View list=root.findViewById(R.id.recyclerView);
        if (list instanceof RecyclerView) {
            list.setBackgroundColor(paper()); list.setPadding(dp(root,10),list.getPaddingTop(),dp(root,10),list.getPaddingBottom());
        }
        for (int id : new int[]{R.id.section1,R.id.section2,R.id.section3,R.id.section4,R.id.section5,R.id.section6,R.id.section7,R.id.section8,R.id.section9}) {
            View section=root.findViewById(id);
            if (section!=null) { paintHeader(section);section.setBackgroundColor(Color.TRANSPARENT);
                section.setPadding(dp(root,12),dp(root,16),dp(root,12),dp(root,8));
                if (section instanceof TextView) {
                    TextView title=(TextView)section;title.setTextColor(ink());title.setAllCaps(false);title.setTextSize(16);
                    title.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
                }
            }
        }
        View scroll=root.findViewById(R.id.scroll);
        if (scroll!=null) { scroll.setBackgroundColor(paper()); paintSettings(scroll); }
    }
    private static void paintSettings(View view) {
        if("no_tint".equals(view.getTag()) || view.getId()==R.id.profileLetter
                || view.getId()==R.id.onDayColor || view.getId()==R.id.onNigthColor
                || view.getId()==R.id.colorsLine || view.getId()==R.id.colorsLine_a)return;
        if (view instanceof CompoundButton) {
            ((CompoundButton)view).setButtonTintList(new ColorStateList(new int[][]{new int[]{android.R.attr.state_checked},new int[]{}},new int[]{accent(),muted()}));
            ((TextView)view).setTextColor(ink()); view.setMinimumHeight(dp(view,48));
            ((TextView)view).setTextSize(14);
        } else if (view instanceof TextView && !((TextView)view).isTextSelectable()) {
            TextView text=(TextView)view;
            if(view.isClickable()) {
                action(view);text.setTextColor(accent());text.setSingleLine(false);text.setMaxLines(3);text.setEllipsize(null);
                text.setPadding(dp(view,12),dp(view,8),dp(view,12),dp(view,8));
            } else if(view.getBackground()==null)text.setTextColor(ink());
        } else if(view.getId()==R.id.onProfileEdit) {
            action(view);
        }
        if (view instanceof ViewGroup) for(int i=0;i<((ViewGroup)view).getChildCount();i++) paintSettings(((ViewGroup)view).getChildAt(i));
        if(view instanceof LinearLayout && ((LinearLayout)view).getOrientation()==LinearLayout.HORIZONTAL) {
            ViewGroup.LayoutParams params=view.getLayoutParams();
            if(params!=null && params.height>0) {params.height=ViewGroup.LayoutParams.WRAP_CONTENT;view.setLayoutParams(params);}
        }
    }
    public static void card(View view, boolean coversOnly) {
        if (!(view.getContext() instanceof MainTabs2) || !(view instanceof CardView)) return;
        CardView card=(CardView)view;
        boolean grid = view.findViewById(R.id.bookCoverFrame) != null;
        card.setCardBackgroundColor(coversOnly || grid ? Color.TRANSPARENT : surface());
        card.setCardElevation(0);
        card.setRadius(dp(view,12));
        View jacket=view.findViewById(R.id.imageParent);
        if(jacket instanceof CardView) ((CardView)jacket).setRadius(TintUtil.coverRadius());
        if (view.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams lp=(ViewGroup.MarginLayoutParams)view.getLayoutParams();
            lp.setMargins(dp(view,4),dp(view,4),dp(view,4),dp(view,8)); view.setLayoutParams(lp);
        }
    }
    public static void coverAction(View view) {
        if (view == null) return;
        action(view);
        view.setPadding(dp(view,17),dp(view,17),dp(view,17),dp(view,17));
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(dark() ? 0x28FFFFFF : 0x18000000),
                new InsetDrawable(shape(view,surface(),12,false),dp(view,12)),null));
    }
}
