package com.dsh.coursetable;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** 应用设置：作息时间、午/晚休、主题色、背景图（透明度/模糊）、圆角、开学日期。 */
public class Settings {

    public static final String DEF_THEME = "#66CCFF";
    public static final String DEF_START = "2026-09-07";

    /** 一节课：节次号 + 起止时间 */
    public static class Period {
        public int n;
        public int start;   // 分钟
        public int end;
        public Period() {}
        public Period(int n, int s, int e) { this.n = n; this.start = s; this.end = e; }
        public int minutes() { return Math.max(0, end - start); }
    }

    /**
     * 一段休息：和第几节同级的一行，自带独立起止时间，可以单独改名。
     * after 是它跟在第几节后面（决定它在作息表里的位置），start/end 是它自己的时段。
     * start &lt; 0 表示时段还没定过（旧设置），这时按前后两节的空档推算。
     */
    public static class Break {
        public int after;
        public String name;
        public int start = -1;
        public int end = -1;
        public Break() {}
        public Break(int after, String name) { this.after = after; this.name = name; }
        public Break(int after, String name, int s, int e) {
            this.after = after; this.name = name; this.start = s; this.end = e;
        }
        public int minutes() { return Math.max(0, end - start); }
    }

    public final List<Period> periods = new ArrayList<Period>();
    public final List<Break> breaks = new ArrayList<Break>();
    /**
     * 作息表的行顺序（长按拖动排出来的），形如 ["P3", "B11:750"]。
     * 只记顺序；某一行不在里面（新加的、或时间刚改过的）就按时间插到它的位置上。
     */
    public final List<String> rowOrder = new ArrayList<String>();
    public String startDate = DEF_START;
    public int theme = 0xFF66CCFF;
    public String bg = "";

    // 外观
    public int bgAlpha = 88;     // 背景图不透明度 %
    public int bgBlur = 10;      // 背景图模糊 %
    public int cardAlpha = 46;   // 课表格子不透明度 %
    public int corner = 14;      // 圆角 dp
    public boolean anim = true;  // 动画
    public int colorMode = 1;    // 配色模式：0=主题色单色 1=背景图取色(糖果色) 2=主题色系多彩
    public boolean remind = false;  // 上课提醒
    public int remindMin = 10;      // 提前多少分钟

    public static Settings defaults() {
        Settings s = new Settings();
        int[][] d = {
                {1, 8 * 60, 8 * 60 + 45},               // 8:00~8:45
                {2, 8 * 60 + 55, 9 * 60 + 40},          // 8:55~9:40
                {3, 10 * 60, 10 * 60 + 45},             // 10:00~10:45
                {4, 10 * 60 + 55, 11 * 60 + 40},        // 10:55~11:40
                {5, 11 * 60 + 50, 12 * 60 + 35},        // 11:50~12:35
                {6, 12 * 60 + 45, 13 * 60 + 30},        // 12:45~13:30
                {7, 14 * 60, 14 * 60 + 45},             // 14:00~14:45
                {8, 14 * 60 + 55, 15 * 60 + 40},        // 14:55~15:40
                {9, 16 * 60, 16 * 60 + 45},             // 16:00~16:45
                {10, 16 * 60 + 55, 17 * 60 + 40},       // 16:55~17:40
                {11, 17 * 60 + 50, 18 * 60 + 35},       // 17:50~18:35
                {12, 19 * 60 + 20, 20 * 60 + 5},        // 19:20~20:05
                {13, 20 * 60 + 15, 21 * 60},            // 20:15~21:00
        };
        for (int[] r : d) s.periods.add(new Period(r[0], r[1], r[2]));
        s.breaks.add(new Break(6, "午休", 13 * 60 + 30, 14 * 60));        // 13:30~14:00
        s.breaks.add(new Break(11, "晚饭", 18 * 60 + 40, 19 * 60 + 20));  // 18:40~19:20
        return s;
    }

    private static File file(Context ctx) { return new File(ctx.getFilesDir(), "settings.json"); }

    public static Settings load(Context ctx) {
        File f = file(ctx);
        if (!f.exists()) return defaults();
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            InputStream in = new FileInputStream(f);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            in.close();
            JSONObject o = new JSONObject(new String(bos.toByteArray(), "UTF-8"));
            Settings s = new Settings();
            s.startDate = o.optString("startDate", DEF_START);
            s.theme = o.optInt("theme", 0xFF66CCFF);
            s.bg = o.optString("bg", "");
            s.bgAlpha = o.optInt("bgAlpha", 88);
            s.bgBlur = o.optInt("bgBlur", 10);
            s.cardAlpha = o.optInt("cardAlpha", 46);
            s.corner = o.optInt("corner", 14);
            s.anim = o.optBoolean("anim", true);
            if (o.has("colorMode")) {
                s.colorMode = o.optInt("colorMode", 1);
            } else {
                s.colorMode = o.optBoolean("bgPalette", true) ? 1 : 2;   // 兼容旧设置
            }
            s.remind = o.optBoolean("remind", false);
            s.remindMin = o.optInt("remindMin", 10);
            JSONArray ps = o.optJSONArray("periods");
            if (ps != null) {
                for (int i = 0; i < ps.length(); i++) {
                    JSONObject p = ps.getJSONObject(i);
                    s.periods.add(new Period(p.optInt("n"), p.optInt("s"), p.optInt("e")));
                }
            }
            JSONArray bs = o.optJSONArray("breaks");
            if (bs != null) {
                for (int i = 0; i < bs.length(); i++) {
                    JSONObject b = bs.getJSONObject(i);
                    Break bk = new Break(b.optInt("after"), b.optString("name", "休息"));
                    bk.start = b.optInt("s", -1);          // 旧设置没有 s/e，保持 -1 由空档推算
                    bk.end = b.optInt("e", -1);
                    s.breaks.add(bk);
                }
            }
            if (s.periods.isEmpty()) s.periods.addAll(defaults().periods);
            JSONArray ro = o.optJSONArray("rowOrder");
            if (ro != null) {
                for (int i = 0; i < ro.length(); i++) {
                    String k = ro.optString(i, "");
                    if (k != null && k.length() > 0) s.rowOrder.add(k);
                }
            }
            s.sort();
            s.settleBreaks();
            return s;
        } catch (Exception e) {
            return defaults();
        }
    }

    public void save(Context ctx) {
        try {
            sort();
            JSONObject o = new JSONObject();
            o.put("startDate", startDate);
            o.put("theme", theme);
            o.put("bg", bg == null ? "" : bg);
            o.put("bgAlpha", bgAlpha);
            o.put("bgBlur", bgBlur);
            o.put("cardAlpha", cardAlpha);
            o.put("corner", corner);
            o.put("anim", anim);
            o.put("colorMode", colorMode);
            o.put("remind", remind);
            o.put("remindMin", remindMin);
            JSONArray ps = new JSONArray();
            for (Period p : periods) {
                JSONObject j = new JSONObject();
                j.put("n", p.n); j.put("s", p.start); j.put("e", p.end);
                ps.put(j);
            }
            o.put("periods", ps);
            JSONArray bs = new JSONArray();
            for (Break b : breaks) {
                JSONObject j = new JSONObject();
                j.put("after", b.after); j.put("name", b.name);
                j.put("s", b.start); j.put("e", b.end);
                bs.put(j);
            }
            o.put("breaks", bs);
            JSONArray ros = new JSONArray();
            for (String k : rowOrder) ros.put(k);
            o.put("rowOrder", ros);
            FileOutputStream fos = new FileOutputStream(file(ctx));
            fos.write(o.toString().getBytes("UTF-8"));
            fos.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void sort() {
        Collections.sort(periods, new Comparator<Period>() {
            public int compare(Period a, Period b) { return a.n - b.n; }
        });
        // 休息段一律按它自己的开始时间排 —— 位置由时间决定，不看它挂在哪一节后面
        Collections.sort(breaks, new Comparator<Break>() {
            public int compare(Break a, Break b) {
                if (a.start != b.start) return a.start - b.start;
                return a.after - b.after;
            }
        });
    }

    /** 把还没定过时段的休息段按前后两节的空档补上（旧设置迁移 / 新加的段） */
    public void settleBreaks() {
        for (Break b : breaks) {
            if (b.start < 0 || b.end < 0) {
                int[] g = gapAround(b.after);
                b.start = g[0];
                b.end = g[1];
            }
            if (b.name == null || b.name.trim().isEmpty()) b.name = "休息";
        }
    }

    // ---------------------------------------------------------------- 查询

    public Period period(int n) {
        for (Period p : periods) if (p.n == n) return p;
        return null;
    }

    public String timeText(int n) {
        Period p = period(n);
        return p == null ? "" : fmt(p.start) + "~" + fmt(p.end);
    }

    public String breakAfter(int n) {
        for (Break b : breaks) if (b.after == n) return b.name;
        return null;
    }

    /** 跟在第 n 节后面的那段休息（可能为 null） */
    public Break breakOf(int n) {
        for (Break b : breaks) if (b.after == n) return b;
        return null;
    }

    /** 第 n 节下课到下一节上课之间的空档；后面没有课了就给一个 45 分钟的默认段 */
    public int[] gapAround(int n) {
        Period prev = period(n);
        Period next = null;
        for (Period p : periods) {
            if (p.n > n && (next == null || p.n < next.n)) next = p;
        }
        if (prev == null) return new int[]{12 * 60, 12 * 60 + 45};
        if (next == null || next.start <= prev.end) {
            return new int[]{prev.end, Math.min(24 * 60 - 1, prev.end + 45)};
        }
        return new int[]{prev.end, next.start};
    }

    /** 第一段的开始时间（用来给休息段一个合理的默认时段） */
    public int firstStart() {
        int m = -1;
        for (Period p : periods) if (m < 0 || p.start < m) m = p.start;
        return m < 0 ? 8 * 60 : m;
    }

    /**
     * 一段休息的时段：定过就用它自己的，没定过就按前后两节的空档推算。
     * 返回 {start, end}。
     */
    public int[] breakRange(Break b) {
        if (b.start >= 0 && b.end >= 0) return new int[]{b.start, b.end};
        return gapAround(b.after);
    }

    /** 午休/晚饭之类的时间区间文字 */
    public String breakTimeText(int after) {
        Break b = breakOf(after);
        if (b == null) return "";
        int[] r = breakRange(b);
        return fmt(r[0]) + "~" + fmt(r[1]);
    }

    /** 休息段的时间区间文字（按对象，支持同一个锚点后有多段） */
    public String breakTimeText(Break b) {
        if (b == null) return "";
        int[] r = breakRange(b);
        return fmt(r[0]) + "~" + fmt(r[1]);
    }

    /**
     * 比较段：手动拖过顺序就按手动顺序（rowOrder 里的下标），没拖过就按开始时间。
     * 设置页和课表都用它，两边顺序才会一致。
     */
    public int compareRows(Break a, Break b) {
        if (!rowOrder.isEmpty()) {
            int ia = rowOrder.indexOf(keyOf(a)), ib = rowOrder.indexOf(keyOf(b));
            if (ia >= 0 && ib >= 0) return ia - ib;
            if (ia >= 0) return -1;
            if (ib >= 0) return 1;
        }
        if (a.start != b.start) return a.start - b.start;
        return a.after - b.after;
    }

    /** 一节课的行标识 */
    public static String keyOf(Period p) { return p == null ? "" : ("P" + p.n); }

    /** 一段休息的行标识（锚点 + 自己的开始时间，保证唯一） */
    public static String keyOf(Break b) {
        if (b == null) return "";
        int[] r = {b.start, b.end};
        return "B" + b.after + ":" + r[0];
    }

    public int nextNumber() {
        int max = 0;
        for (Period p : periods) max = Math.max(max, p.n);
        return max + 1;
    }

    public static String fmt(int minutes) {
        if (minutes < 0) minutes = 0;
        int h = (minutes / 60) % 24, m = minutes % 60;
        return h + ":" + String.format(Locale.US, "%02d", m);
    }

    // ---------------------------------------------------------------- 日期

    public static final SimpleDateFormat SDF = new SimpleDateFormat("yyyy-MM-dd", Locale.US);

    public long weekStartMillis() {
        try {
            Calendar c = Calendar.getInstance();
            c.setTime(SDF.parse(startDate));
            c.set(Calendar.HOUR_OF_DAY, 0);
            c.set(Calendar.MINUTE, 0);
            c.set(Calendar.SECOND, 0);
            c.set(Calendar.MILLISECOND, 0);
            int dow = c.get(Calendar.DAY_OF_WEEK);
            int delta = (dow == Calendar.SUNDAY) ? -6 : (Calendar.MONDAY - dow);
            c.add(Calendar.DAY_OF_MONTH, delta);
            return c.getTimeInMillis();
        } catch (Exception e) {
            return System.currentTimeMillis();
        }
    }

    public int daysSinceStart() {
        Calendar t = Calendar.getInstance();
        t.setTimeInMillis(System.currentTimeMillis());
        t.set(Calendar.HOUR_OF_DAY, 0); t.set(Calendar.MINUTE, 0);
        t.set(Calendar.SECOND, 0); t.set(Calendar.MILLISECOND, 0);
        long diff = t.getTimeInMillis() - weekStartMillis();
        return (int) Math.floor(diff / 86400000.0);
    }

    public int weekOfToday() {
        int d = daysSinceStart();
        if (d < 0) return 1;
        return d / 7 + 1;
    }

    public static int todayDow() {
        Calendar c = Calendar.getInstance();
        int dow = c.get(Calendar.DAY_OF_WEEK);
        return dow == Calendar.SUNDAY ? 7 : dow - 1;
    }

    public static String dateOfWeek(long weekStartMillis, int week, int day) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(weekStartMillis);
        c.add(Calendar.DAY_OF_MONTH, (week - 1) * 7 + (day - 1));
        return (c.get(Calendar.MONTH) + 1) + "/" + c.get(Calendar.DAY_OF_MONTH);
    }

    public static String weekdayCn(int dow) {
        String[] n = {"", "周一", "周二", "周三", "周四", "周五", "周六", "周日"};
        return dow >= 1 && dow <= 7 ? n[dow] : "";
    }
}
