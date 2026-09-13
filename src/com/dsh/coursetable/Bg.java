package com.dsh.coursetable;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.File;
import java.io.FileOutputStream;

/** 背景图：原图 + 横/竖两套手动裁切（用户自己裁，避免软件拉伸变形）。 */
public class Bg {

    private static Bitmap cache;
    private static String cacheKey;

    /**
     * 背景图「改过没有」的计数器。
     *
     * 主界面判断"设置有没有变、要不要重建"用的是 {@link Settings} 的字符串签名，
     * 而签名里如果只放"有没有背景图"这个布尔值，就会出现：已经有背景图时换一张图、
     * 或者重新裁切，布尔值一直是 true → 签名不变 → 主界面不重建 → 背景不刷新，
     * 必须退出应用重进才生效。这个计数器在所有写入点自增，主界面把它算进签名即可。
     */
    private static int generation;

    public static int generation() { return generation; }

    /** 背景图内容变了：清缓存，并让主界面知道要重新取图 */
    public static void markChanged() {
        generation++;
        cache = null;
        cacheKey = null;
    }

    /** 旧版本用的是 bg.img，这里做一次迁移，避免升级后背景“消失” */
    private static void migrate(Context ctx) {
        try {
            File src = new File(ctx.getFilesDir(), "bg_src.img");
            File legacy = new File(ctx.getFilesDir(), "bg.img");
            if (!src.exists() && legacy.exists() && legacy.length() > 0) {
                java.io.InputStream in = new java.io.FileInputStream(legacy);
                java.io.FileOutputStream out = new java.io.FileOutputStream(src);
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                out.close();
                in.close();
            }
        } catch (Throwable ignore) {}
    }

    public static File srcFile(Context ctx) {
        migrate(ctx);
        return new File(ctx.getFilesDir(), "bg_src.img");
    }
    public static File cropFile(Context ctx, boolean landscape) {
        return new File(ctx.getFilesDir(), landscape ? "bg_land.img" : "bg_port.img");
    }

    public static boolean hasSrc(Context ctx) {
        File f = srcFile(ctx);   // srcFile 内部会做旧版迁移
        return f.exists() && f.length() > 0;
    }

    public static boolean hasCrop(Context ctx, boolean landscape) {
        File f = cropFile(ctx, landscape);
        return f.exists() && f.length() > 0;
    }

    /** 有任何可用背景（裁切图或原图） */
    public static boolean exists(Context ctx) {
        return hasCrop(ctx, true) || hasCrop(ctx, false) || hasSrc(ctx);
    }

    public static void clearAll(Context ctx) {
        srcFile(ctx).delete();
        cropFile(ctx, true).delete();
        cropFile(ctx, false).delete();
        markChanged();
    }

    public static boolean isLandscape(Context ctx) {
        return ctx.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
    }

    public static void saveCrop(Context ctx, boolean landscape, Bitmap bmp) {
        try {
            FileOutputStream out = new FileOutputStream(cropFile(ctx, landscape));
            bmp.compress(Bitmap.CompressFormat.JPEG, 92, out);
            out.close();
            markChanged();
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    public static Bitmap loadFile(File f, int maxW) {
        if (f == null || !f.exists()) return null;
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            int sample = 1;
            while (maxW > 0 && o.outWidth / sample > maxW * 2) sample *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample;
            return BitmapFactory.decodeFile(f.getAbsolutePath(), o2);
        } catch (Throwable t) {
            return null;
        }
    }

    public static Bitmap loadSrc(Context ctx, int maxW) { return loadFile(srcFile(ctx), maxW); }

    public static Bitmap thumb(Context ctx) {
        Bitmap b = loadFile(cropFile(ctx, true), 180);
        if (b == null) b = loadFile(cropFile(ctx, false), 180);
        if (b == null) b = loadSrc(ctx, 180);
        return b;
    }

    /** 居中裁到指定比例（只在用户没裁过对应方向时兜底，保证不变形） */
    public static Bitmap fitTo(Bitmap src, float aspect) {
        if (src == null || aspect <= 0) return src;
        int w = src.getWidth(), h = src.getHeight();
        float cur = (float) w / h;
        int nw = w, nh = h;
        if (cur > aspect) nw = Math.round(h * aspect);
        else nh = Math.round(w / aspect);
        if (nw == w && nh == h) return src;
        int x = (w - nw) / 2, y = (h - nh) / 2;
        try {
            Bitmap out = Bitmap.createBitmap(src, x, y, Math.max(1, nw), Math.max(1, nh));
            return out;
        } catch (Throwable t) {
            return src;
        }
    }

    /** 取当前方向该用的背景图（已模糊），带缓存 */
    public static Bitmap background(Context ctx, int maxW, int blurPct, float aspect) {
        boolean land = isLandscape(ctx);
        File f = hasCrop(ctx, land) ? cropFile(ctx, land) : srcFile(ctx);
        if (!f.exists()) return null;
        String key = maxW + ":" + blurPct + ":" + aspect + ":" + f.getName() + ":" + f.lastModified();
        if (cache != null && !cache.isRecycled() && key.equals(cacheKey)) return cache;
        Bitmap b = loadFile(f, maxW);
        if (b == null) return null;
        if (!hasCrop(ctx, land)) b = fitTo(b, aspect);   // 没裁过：居中裁，绝不拉伸
        if (blurPct > 0) {
            Bitmap bl = blur(b, blurPct);
            if (bl != null) b = bl;
        }
        cache = b;
        cacheKey = key;
        return b;
    }

    /** 旧接口（兼容）：取当前方向、不额外处理 */
    public static Bitmap processed(Context ctx, int maxW, int blurPct) {
        return background(ctx, maxW, blurPct, 0);
    }

    public static Bitmap blur(Bitmap src, int pct) {
        try {
            int w = Math.max(1, src.getWidth() / 4);
            int h = Math.max(1, src.getHeight() / 4);
            Bitmap small = Bitmap.createScaledBitmap(src, w, h, true);
            int[] p = new int[w * h];
            small.getPixels(p, 0, w, 0, 0, w, h);
            int radius = Math.max(1, Math.round(pct / 100f * Math.min(w, h) / 7f));
            for (int i = 0; i < 2; i++) {
                boxH(p, w, h, radius);
                boxV(p, w, h, radius);
            }
            small.setPixels(p, 0, w, 0, 0, w, h);
            Bitmap out = Bitmap.createScaledBitmap(small, src.getWidth(), src.getHeight(), true);
            small.recycle();
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void boxH(int[] p, int w, int h, int r) {
        int div = 2 * r + 1;
        int[] line = new int[w];
        for (int y = 0; y < h; y++) {
            int base = y * w;
            int a = 0, rr = 0, g = 0, b = 0;
            for (int x = -r; x <= r; x++) {
                int c = p[base + Math.min(w - 1, Math.max(0, x))];
                a += (c >>> 24); rr += (c >> 16) & 0xFF; g += (c >> 8) & 0xFF; b += c & 0xFF;
            }
            for (int x = 0; x < w; x++) {
                line[x] = ((a / div) << 24) | ((rr / div) << 16) | ((g / div) << 8) | (b / div);
                int add = p[base + Math.min(w - 1, x + r + 1)];
                int rem = p[base + Math.max(0, x - r)];
                a += (add >>> 24) - (rem >>> 24);
                rr += ((add >> 16) & 0xFF) - ((rem >> 16) & 0xFF);
                g += ((add >> 8) & 0xFF) - ((rem >> 8) & 0xFF);
                b += (add & 0xFF) - (rem & 0xFF);
            }
            System.arraycopy(line, 0, p, base, w);
        }
    }

    private static void boxV(int[] p, int w, int h, int r) {
        int div = 2 * r + 1;
        int[] col = new int[h];
        for (int x = 0; x < w; x++) {
            int a = 0, rr = 0, g = 0, b = 0;
            for (int y = -r; y <= r; y++) {
                int c = p[Math.min(h - 1, Math.max(0, y)) * w + x];
                a += (c >>> 24); rr += (c >> 16) & 0xFF; g += (c >> 8) & 0xFF; b += c & 0xFF;
            }
            for (int y = 0; y < h; y++) {
                col[y] = ((a / div) << 24) | ((rr / div) << 16) | ((g / div) << 8) | (b / div);
                int add = p[Math.min(h - 1, y + r + 1) * w + x];
                int rem = p[Math.max(0, y - r) * w + x];
                a += (add >>> 24) - (rem >>> 24);
                rr += ((add >> 16) & 0xFF) - ((rem >> 16) & 0xFF);
                g += ((add >> 8) & 0xFF) - ((rem >> 8) & 0xFF);
                b += (add & 0xFF) - (rem & 0xFF);
            }
            for (int y = 0; y < h; y++) p[y * w + x] = col[y];
        }
    }
}
