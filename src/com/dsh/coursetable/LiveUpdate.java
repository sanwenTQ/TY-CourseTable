package com.dsh.coursetable;

import android.app.Notification;
import android.os.Build;
import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Android 16（API 36）「实时更新」（进度式通知）适配。
 *
 * Android 16 起，实况通知改走公开 API：
 * <pre>
 *   Notification.ProgressStyle style = new Notification.ProgressStyle()
 *           .setStyledByProgress(false)
 *           .setProgress(0)
 *           .setProgressSegments(segs);
 *   builder.setStyle(style);
 * </pre>
 * 系统会把符合条件的常驻通知提升为状态栏胶囊 / 锁屏卡片（即以前各家私有的"灵动岛"）；
 * 系统版本低于 16、用户关掉了实时更新开关、或通知不满足条件的机型，
 * 就按普通通知横幅显示 —— 这些都不需要应用额外处理。
 *
 * <p>为什么要用反射：本项目编译用的是 {@code platforms/android-34/android.jar}，
 * 里面还没有 {@code ProgressStyle} 这个类（只有 API 34 就存在的 {@code Notification.Style}）。
 * 反射的代价是每步都得自己兜住异常，好处是不必为此换 SDK，也不会因为厂商 ROM 的实现差异崩掉。
 *
 * <p>降级策略：任何一步在真机上不存在或签名不同，就整段跳过并返回 false，
 * 调用方照原样发普通通知 —— 提醒本身绝不会失败。
 */
final class LiveUpdate {

    /** Android 16 = API 36 */
    private static final int API_LIVE_UPDATE = 36;
    private static final String TAG = "TYCourseTable";

    /**
     * {@code ProgressStyle.Segment} 的构造参数顺序。
     * 平台文档是 {@code Segment(int length, int color)}（先是长度/权重，再是颜色）。
     * 万一真机上颜色和长度看着是反的，把这里改成 false 即可（一处开关）。
     */
    private static final boolean SEGMENT_LENGTH_FIRST = true;

    private LiveUpdate() {}

    /** 这台设备是否具备走实时更新的条件（系统 >= 16 且运行时确实有这个类） */
    static boolean supported() {
        return Build.VERSION.SDK_INT >= API_LIVE_UPDATE && progressStyleClass() != null;
    }

    /**
     * 把已经配好的通知改成「实时更新」。
     *
     * @param b         通知构建器（此时已设好自定义布局、图标、点击意图等）
     * @param shortText 状态栏胶囊里的短文案（一般给上课时间，如 "8:00"）
     * @param accent    这一节课在时间轴上的颜色
     * @param classAt   上课时刻（毫秒）
     * @param classEnd  下课时刻（毫秒）
     * @return true 表示确实套上了 ProgressStyle，系统会按实时更新渲染
     */
    static boolean apply(Notification.Builder b, CharSequence shortText, int accent,
                         long classAt, long classEnd) {
        if (b == null || !supported()) return false;

        long now = System.currentTimeMillis();
        try {
            Class<?> psCls = progressStyleClass();
            Object style = psCls.getDeclaredConstructor().newInstance();

            // 时间轴两段：课前提醒（灰）+ 这一节课（主题色）
            List<Object> segs = new ArrayList<Object>();
            Object pre = newSegment(0xFF9E9E9E, minutes(now, classAt));
            Object cls = newSegment(accent, minutes(classAt, classEnd));
            if (pre != null) segs.add(pre);
            if (cls != null) segs.add(cls);
            if (!segs.isEmpty()) call(style, "setProgressSegments", List.class, segs);

            // 卡片底色不要跟着进度变；刚提醒时进度就是 0（课还没开始）
            call(style, "setStyledByProgress", boolean.class, Boolean.FALSE);
            call(style, "setProgress", int.class, Integer.valueOf(0));

            // ProgressStyle 继承自 Notification.Style，这里转成编译期就有的父类型
            b.setStyle((Notification.Style) style);

            // 状态栏胶囊里的短文案（Android 16 新增；没有就跳过）
            if (shortText != null && shortText.length() > 0) {
                call(b, "setShortCriticalText", CharSequence.class, shortText);
            }
            // Android 15 QPR 上的旧预览开关，16 起由 ProgressStyle 取代；存在才调
            call(b, "setRequestPromotedOngoing", boolean.class, Boolean.TRUE);

            // 实时更新的"动"的部分：倒计时到上课，下课后通知自己收走
            b.setWhen(classAt).setUsesChronometer(true).setChronometerCountDown(true);
            if (classEnd > now) {
                try { b.setTimeoutAfter(Math.max(1000L, classEnd - now)); } catch (Throwable ignore) {}
            }

            Log.i(TAG, "live update applied (segments=" + segs.size() + ")");
            return true;
        } catch (Throwable t) {
            // 厂商 ROM 上没有这个类/签名不一致：静默退回普通通知
            Log.i(TAG, "live update unavailable: " + t);
            return false;
        }
    }

    // ---------------- 反射小工具（每一步都自己兜异常） ----------------

    private static Class<?> progressStyleClass() {
        try {
            return Class.forName("android.app.Notification$ProgressStyle");
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object newSegment(int color, int length) {
        try {
            Class<?> c = Class.forName("android.app.Notification$ProgressStyle$Segment");
            for (Constructor<?> ct : c.getConstructors()) {
                Class<?>[] p = ct.getParameterTypes();
                if (p.length == 2 && p[0] == int.class && p[1] == int.class) {
                    return SEGMENT_LENGTH_FIRST ? ct.newInstance(length, color)
                                                : ct.newInstance(color, length);
                }
            }
        } catch (Throwable ignore) {}
        return null;
    }

    private static Object call(Object target, String name, Class<?> type, Object arg) {
        if (target == null) return null;
        try {
            Method m = target.getClass().getMethod(name, type);
            m.setAccessible(true);
            return m.invoke(target, arg);
        } catch (Throwable t) {
            return null;
        }
    }

    private static int minutes(long from, long to) {
        long m = (to - from) / 60_000L;
        if (m < 1L) m = 1L;
        if (m > 600L) m = 600L;
        return (int) m;
    }
}
