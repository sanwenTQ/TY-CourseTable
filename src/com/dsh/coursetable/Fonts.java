package com.dsh.coursetable;

import android.content.Context;
import android.graphics.Typeface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** 全局字体（HarmonyOS Sans SC，年轻现代的几何黑体；缺字自动回退系统字体）。 */
public class Fonts {

    private static Typeface regular;
    private static Typeface bold;
    private static boolean loaded;

    public static void init(Context ctx) {
        if (loaded) return;
        loaded = true;
        regular = load(ctx, "fonts/HarmonyOS_Sans_SC_Regular.ttf", Typeface.DEFAULT);
        bold = load(ctx, "fonts/HarmonyOS_Sans_SC_Bold.ttf", Typeface.DEFAULT_BOLD);
    }

    private static Typeface load(Context ctx, String path, Typeface fallback) {
        try {
            return Typeface.createFromAsset(ctx.getAssets(), path);
        } catch (Throwable t) {
            return fallback;
        }
    }

    public static Typeface regular(Context ctx) { init(ctx); return regular; }

    /** 递归把字体应用到视图树里的所有文字 */
    public static void apply(View root) {
        if (root == null) return;
        List<View> stack = new ArrayList<View>();
        stack.add(root);
        while (!stack.isEmpty()) {
            View v = stack.remove(stack.size() - 1);
            if (v instanceof TextView) {
                TextView tv = (TextView) v;
                Typeface cur = tv.getTypeface();
                boolean wantBold = cur != null && cur.isBold();
                Typeface t = wantBold ? bold : regular;
                if (t != null) tv.setTypeface(t, wantBold ? Typeface.BOLD : Typeface.NORMAL);
            }
            if (v instanceof ViewGroup) {
                ViewGroup g = (ViewGroup) v;
                for (int i = 0; i < g.getChildCount(); i++) stack.add(g.getChildAt(i));
            }
        }
    }
}
