package com.foobnix.ui2;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;

import com.foobnix.pdf.info.R;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

public final class LibraryHeader extends LinearLayout {
    private final List<int[]> positions = new ArrayList<>();
    private final Map<View, Integer> collapsedViews = new IdentityHashMap<>();
    private final ImageView overflow;
    private boolean expanded;

    public LibraryHeader(Context context, AttributeSet attrs) {
        super(context, attrs);
        overflow = new ImageView(context);
        overflow.setTag("library-header-actions");
        overflow.setImageResource(R.drawable.ic_catalog_filter);
        overflow.setContentDescription(context.getString(R.string.filter));
        overflow.setClickable(true);
        overflow.setFocusable(true);
        overflow.setVisibility(GONE);
        overflow.setOnClickListener(view -> {
            expanded = !expanded;
            overflow.setSelected(expanded);
            requestLayout();
        });
        addView(overflow, new LayoutParams(LibraryDesign.dp(this, 48), LibraryDesign.dp(this, 48)));
    }

    @Override protected void onFinishInflate() {
        super.onFinishInflate();
        removeView(overflow);
        addView(overflow);
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        int available = Math.max(1, width - getPaddingLeft() - getPaddingRight());
        boolean compact = available / getResources().getDisplayMetrics().density < 600 && getChildCount() > 3;
        for (Map.Entry<View, Integer> entry : collapsedViews.entrySet()) {
            if (entry.getKey().getVisibility() == INVISIBLE) entry.getKey().setVisibility(entry.getValue());
        }
        collapsedViews.clear();
        int primary = -1;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child != overflow && ((LayoutParams) child.getLayoutParams()).weight > 0) {
                primary = i;
                break;
            }
        }
        boolean hasOverflow = false;
        if (compact && primary >= 0) {
            for (int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                if (child == overflow || i == primary || (i == 0 && primary == 1)) continue;
                if (child.getVisibility() == VISIBLE) {
                    hasOverflow = true;
                    if (!expanded) {
                        collapsedViews.put(child, child.getVisibility());
                        child.setVisibility(INVISIBLE);
                    }
                }
            }
        }
        overflow.setVisibility(hasOverflow ? VISIBLE : GONE);
        LibraryDesign.action(overflow);
        int gap = LibraryDesign.dp(this, 6);
        int flexible = -1, fixedWidth = 0, before = 0, visible = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() != VISIBLE) continue;
            LayoutParams lp = (LayoutParams) child.getLayoutParams();
            if (lp.weight > 0 && flexible < 0) { flexible = i; before = fixedWidth; }
            else {
                int desired = lp.width > 0 ? Math.min(lp.width, available) : available;
                child.measure(MeasureSpec.makeMeasureSpec(desired, lp.width > 0 ? MeasureSpec.EXACTLY : MeasureSpec.AT_MOST),
                        MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                fixedWidth += child.getMeasuredWidth() + lp.leftMargin + lp.rightMargin + gap;
            }
            visible++;
        }
        if (flexible >= 0) {
            View child = getChildAt(flexible);
            LayoutParams lp = (LayoutParams) child.getLayoutParams();
            int remainder = available - fixedWidth;
            // The title/search gets a readable first row. Remaining actions wrap below it.
            int desired = remainder >= LibraryDesign.dp(this, 120) ? remainder : available - before;
            child.measure(MeasureSpec.makeMeasureSpec(Math.max(1, desired - lp.leftMargin - lp.rightMargin), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        }
        positions.clear();
        int x = 0, y = getPaddingTop(), rowHeight = 0;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            LayoutParams lp = (LayoutParams) child.getLayoutParams();
            if (child.getVisibility() != VISIBLE) { positions.add(new int[]{0, 0}); continue; }
            int childWidth = child.getMeasuredWidth() + lp.leftMargin + lp.rightMargin;
            if (x > 0 && x + childWidth > available) { y += rowHeight + gap; x = 0; rowHeight = 0; }
            positions.add(new int[]{getPaddingLeft() + x + lp.leftMargin, y + lp.topMargin});
            x += childWidth + gap;
            rowHeight = Math.max(rowHeight, child.getMeasuredHeight() + lp.topMargin + lp.bottomMargin);
        }
        int desiredHeight = y + rowHeight + getPaddingBottom();
        setMeasuredDimension(resolveSize(width, widthSpec), resolveSize(desiredHeight, heightSpec));
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
        for (int i = 0; i < getChildCount() && i < positions.size(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() != VISIBLE) continue;
            int[] at = positions.get(i);
            int x = rtl ? getWidth() - at[0] - child.getMeasuredWidth() : at[0];
            child.layout(x, at[1], x + child.getMeasuredWidth(), at[1] + child.getMeasuredHeight());
        }
    }
}
