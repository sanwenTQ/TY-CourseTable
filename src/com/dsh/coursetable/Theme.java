package com.dsh.coursetable;

import android.graphics.Bitmap;
import android.graphics.Color;

/**
 * MD3 风格的动态配色：从一个种子色推导出整套 color roles（tonal palette 近似实现）。
 */
public class Theme {

    public final int seed;
    public final float hue;
    public final float sat;

    public final int primary;
    public final int onPrimary;
    public final int primaryContainer;
    public final int onPrimaryContainer;
    public final int secondaryContainer;
    public final int onSecondaryContainer;
    public final int tertiary;
    public final int tertiaryContainer;
    public final int onTertiaryContainer;
    public final int surface;
    public final int surfaceContainerLow;
    public final int surfaceContainer;
    public final int surfaceContainerHigh;
    public final int onSurface;
    public final int onSurfaceVariant;
    public final int outline;
    public final int outlineVariant;
    public final int error;

    /** 星期几的容器色 / 前景色 */
    public final int[] dayContainer = new int[8];
    public final int[] onDay = new int[8];

    private static final float[] DAY_HUE = {0, 0, 16, -14, 32, -30, 48, -46};

    public static Theme of(int seed) { return new Theme(seed); }

    private Theme(int seed) {
        this.seed = seed;
        float[] hsv = new float[3];
        Color.colorToHSV(seed, hsv);
        hue = hsv[0];
        sat = clamp(hsv[1], 0.30f, 0.85f);

        primary = hsvc(hue, clamp(sat * 0.95f, 0.32f, 0.78f), 0.90f);
        onPrimary = lum(primary) > 0.55 ? hsvc(hue, sat * 0.9f, 0.22f) : Color.WHITE;
        primaryContainer = hsvc(hue, sat * 0.42f, 0.97f);
        onPrimaryContainer = hsvc(hue, sat * 0.92f, 0.30f);
        secondaryContainer = hsvc(hue + 10f, sat * 0.30f, 0.95f);
        onSecondaryContainer = hsvc(hue + 10f, sat * 0.85f, 0.30f);
        tertiary = hsvc(hue + 58f, clamp(sat * 0.75f, 0.30f, 0.68f), 0.93f);
        tertiaryContainer = hsvc(hue + 58f, sat * 0.34f, 0.96f);
        onTertiaryContainer = hsvc(hue + 58f, sat * 0.85f, 0.32f);

        surface = hsvc(hue, 0.055f, 0.992f);
        surfaceContainerLow = hsvc(hue, 0.065f, 0.975f);
        surfaceContainer = hsvc(hue, 0.075f, 0.955f);
        surfaceContainerHigh = hsvc(hue, 0.085f, 0.935f);
        onSurface = hsvc(hue, 0.28f, 0.17f);
        onSurfaceVariant = hsvc(hue, 0.16f, 0.42f);
        outline = hsvc(hue, 0.12f, 0.56f);
        outlineVariant = hsvc(hue, 0.10f, 0.82f);
        error = 0xFFB3261E;

        dayContainer[0] = hsvc(hue, 0.05f, 0.92f);
        onDay[0] = onSurfaceVariant;
        for (int d = 1; d <= 7; d++) {
            float h = hue + DAY_HUE[d];
            dayContainer[d] = hsvc(h, sat * 0.36f, 0.965f);
            onDay[d] = hsvc(h, sat * 0.95f, 0.30f);
        }
    }

    /** 第 d 天的基础色（无背景图时用主题色做色相偏移） */
    public int daySeed(int d) {
        if (d < 1 || d > 7) return seed;
        float h = hue + DAY_HUE[d];
        return hsvc(h, clamp(sat * 0.80f, 0.25f, 0.85f), 0.93f);
    }

    /** 从图里取 n 个主色（按面积权重，用于课表格子配色） */
    public static int[] extractPalette(Bitmap src, int n, int fallback) {
        if (src == null || n <= 0) return new int[]{fallback};
        try {
            int w = 72, h = 72;
            Bitmap bmp = Bitmap.createScaledBitmap(src, w, h, true);
            int[] px = new int[w * h];
            bmp.getPixels(px, 0, w, 0, 0, w, h);
            double[] weight = new double[12];
            double[] sr = new double[12], sg = new double[12], sb = new double[12];
            float[] hsv = new float[3];
            for (int c : px) {
                if ((c >>> 24) < 100) continue;
                Color.colorToHSV(c, hsv);
                if (hsv[1] < 0.12f || hsv[2] < 0.16f || hsv[2] > 0.99f) continue;
                int b = (int) (hsv[0] / 30f) % 12;
                double wgt = 0.35 + hsv[1] * hsv[2];
                weight[b] += wgt;
                sr[b] += Color.red(c) * wgt; sg[b] += Color.green(c) * wgt; sb[b] += Color.blue(c) * wgt;
            }
            Integer[] idx = new Integer[12];
            for (int i = 0; i < 12; i++) idx[i] = i;
            final double[] wt = weight;
            java.util.Arrays.sort(idx, new java.util.Comparator<Integer>() {
                public int compare(Integer a, Integer b) { return Double.compare(wt[b], wt[a]); }
            });
            java.util.List<Integer> out = new java.util.ArrayList<Integer>();
            for (int i = 0; i < 12 && out.size() < n; i++) {
                int b = idx[i];
                if (weight[b] <= 0) continue;
                int r = (int) (sr[b] / weight[b]);
                int g = (int) (sg[b] / weight[b]);
                int bl = (int) (sb[b] / weight[b]);
                float[] o = new float[3];
                Color.colorToHSV(Color.rgb(r, g, bl), o);
                o[1] = clamp(o[1], 0.34f, 0.85f);
                o[2] = clamp(o[2], 0.72f, 1.0f);
                out.add(Color.HSVToColor(o));
            }
            if (out.isEmpty()) return new int[]{fallback};
            // 不够 n 个就按色相循环补
            int m = out.size();
            int[] arr = new int[n];
            for (int i = 0; i < n; i++) {
                if (i < m) arr[i] = out.get(i);
                else {
                    float[] o = new float[3];
                    Color.colorToHSV(out.get(i % m), o);
                    o[0] = (o[0] + 26f * (i / m)) % 360f;
                    arr[i] = Color.HSVToColor(o);
                }
            }
            return arr;
        } catch (Throwable t) {
            return new int[]{fallback};
        }
    }

    /** 糖果色盘（色相 → 糖果色） */
    private static final float[] CANDY_HUE = {352, 18, 45, 90, 150, 180, 205, 235, 265, 290, 318, 335};
    private static final int[] CANDY = {
            0xFFFFB3C1, 0xFFFFD6A5, 0xFFFDFFB6, 0xFFCAFFBF, 0xFFB9FBC0, 0xFF9BF6FF,
            0xFFA0C4FF, 0xFFBDB2FF, 0xFFD0BFFF, 0xFFE4C1F9, 0xFFFFC6FF, 0xFFFFADAD
    };

    /** 把任意颜色吸附到最接近的糖果色（保留原图色相倾向，观感更年轻） */
    public static int candy(int color) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        int best = 0;
        float bestD = 999f;
        for (int i = 0; i < CANDY_HUE.length; i++) {
            float d = Math.abs(((hsv[0] - CANDY_HUE[i] + 540f) % 360f) - 180f);
            if (d < bestD) { bestD = d; best = i; }
        }
        return CANDY[best];
    }

    public static int hsvc(float h, float s, float v) {
        return Color.HSVToColor(new float[]{((h % 360f) + 360f) % 360f, clamp(s, 0f, 1f), clamp(v, 0.04f, 1f)});
    }

    public static int withAlpha(int color, int a) {
        return (color & 0x00FFFFFF) | ((a & 0xFF) << 24);
    }

    /** 0..100 → alpha */
    public static int pct(int color, int percent) {
        return withAlpha(color, Math.round(255f * clamp(percent, 0, 100) / 100f));
    }

    public static int mix(int c1, int c2, float t) {
        t = clamp(t, 0f, 1f);
        int r = (int) (Color.red(c1) + (Color.red(c2) - Color.red(c1)) * t);
        int g = (int) (Color.green(c1) + (Color.green(c2) - Color.green(c1)) * t);
        int b = (int) (Color.blue(c1) + (Color.blue(c2) - Color.blue(c1)) * t);
        return Color.rgb(r, g, b);
    }

    public static int shade(int color, float factor) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hsv[2] = clamp(hsv[2] * factor, 0.05f, 1f);
        return Color.HSVToColor(hsv);
    }

    public static float lum(int color) {
        return (float) ((0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) / 255.0);
    }

    public static float clamp(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }
    public static int clamp(int v, int lo, int hi) { return v < lo ? lo : (v > hi ? hi : v); }

    public static int onColor(int bg) { return lum(bg) > 0.6 ? 0xFF14212B : Color.WHITE; }

    /** 从图片里挑一个最“主”的鲜艳色，调成适合当种子色的明度/饱和度 */
    public static int extract(Bitmap src, int fallback) {
        if (src == null) return fallback;
        try {
            int w = 64, h = 64;
            Bitmap bmp = Bitmap.createScaledBitmap(src, w, h, true);
            int[] px = new int[w * h];
            bmp.getPixels(px, 0, w, 0, 0, w, h);
            double[] weight = new double[12];
            double[] sr = new double[12], sg = new double[12], sb = new double[12];
            float[] hsv = new float[3];
            for (int c : px) {
                if ((c >>> 24) < 100) continue;
                Color.colorToHSV(c, hsv);
                if (hsv[1] < 0.14f || hsv[2] < 0.18f || hsv[2] > 0.985f) continue;
                int bucket = (int) (hsv[0] / 30f) % 12;
                double wgt = hsv[1] * hsv[2];
                weight[bucket] += wgt;
                sr[bucket] += Color.red(c) * wgt;
                sg[bucket] += Color.green(c) * wgt;
                sb[bucket] += Color.blue(c) * wgt;
            }
            int best = -1;
            for (int i = 0; i < 12; i++) {
                if (weight[i] <= 0) continue;
                if (best < 0 || weight[i] > weight[best]) best = i;
            }
            if (best < 0) return fallback;
            int r = (int) (sr[best] / weight[best]);
            int g = (int) (sg[best] / weight[best]);
            int b = (int) (sb[best] / weight[best]);
            float[] out = new float[3];
            Color.colorToHSV(Color.rgb(r, g, b), out);
            out[1] = clamp(out[1], 0.38f, 0.80f);
            out[2] = clamp(out[2], 0.86f, 1.0f);
            return Color.HSVToColor(out);
        } catch (Throwable t) {
            return fallback;
        }
    }
}
