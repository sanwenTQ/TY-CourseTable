package com.dsh.coursetable;

import android.app.Activity;
import android.content.Intent;
import android.content.res.Configuration;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.Path;
import android.animation.ValueAnimator;
import android.animation.Animator;
import android.graphics.RectF;
import android.view.animation.PathInterpolator;
import android.app.ActivityOptions;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeSet;

/**
 * 我的课表 1.3：MD3 界面，按“节次 × 星期”的表格逐周查看。
 * 主题色可自定义/动态取色；背景图可调透明度与模糊；圆角可调；带过渡动画。
 */
public class MainActivity extends Activity {

    private static final String TAG = "CourseTable";
    private static final int REQ_PICK = 1001;
    private static final int MAX_PERIOD = 20;

    private static final String[] DAY_NAME  = {"其他", "星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日"};
    private static final String[] DAY_SHORT = {"其他", "周一", "周二", "周三", "周四", "周五", "周六", "周日"};

    private final List<Course> courses = new ArrayList<Course>();
    private Settings set;
    private Theme th;
    private boolean hasBg;

    private String semester = "";
    private String sourceName = "";
    private int maxWeek = 16;
    private int currentWeek = 1;
    private boolean showAll = false;
    private boolean parsing = false;

    private FrameLayout rootFrame;
    private LinearLayout rootBox;
    private LinearLayout weekBar;
    private LinearLayout content;
    private TextView infoText;
    private ImageView btnList, btnImport, btnSettings;
    private TextView headerSub;

    private int lastContentW = -1;
    private int lastContentH = -1;

    // 每天的颜色（有背景图时来自背景图的动态取色）
    private final int[] daySeed = new int[8];
    private final int[] dayFill = new int[8];
    private final int[] dayName = new int[8];
    private final int[] dayRoom = new int[8];
    private final int[] dayTeacher = new int[8];
    private View dimOverlay;                     // 展开动画遮罩
    private View detailCard;                     // 展开的详情卡片
    private View detailSource;                   // 来源格子
    private RectF detailSourceRect;              // 打开时记下的格子矩形（保证从哪来回哪去）
    private LinearLayout detailContent;
    private boolean detailOpen;
    private int[] palette;                       // 背景图取出的糖果色盘
    private final Map<String, Integer> courseColorIdx = new HashMap<String, Integer>();

    // ---------------------------------------------------------------- 生命周期

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        PDFBoxResourceLoader.init(getApplicationContext());
        Fonts.init(getApplicationContext());
        Store.Data d = Store.load(this);
        if (d != null) {
            courses.addAll(d.courses);
            semester = d.semester;
            sourceName = d.source;
            maxWeek = Math.max(1, d.maxWeek);
        }
        set = Settings.load(this);
        th = Theme.of(set.theme);
        hasBg = Bg.exists(this);
        computeDayColors();
        if (!courses.isEmpty()) currentWeek = clampWeek(set.weekOfToday());

        lastSig = settingsSigOf(set);
        buildUi();
        render();

        Intent it0 = getIntent();
        if (it0 != null && it0.getBooleanExtra("settings", false)) {
            startActivity(new Intent(this, SettingsActivity.class));
        }
        Uri start = uriFrom(it0);
        if (start != null) importFrom(start);
    }

    private String lastSig = "";

    @Override
    protected void onResume() {
        super.onResume();
        try { Reminder.reschedule(this); } catch (Throwable ignore) {}
        Settings s = Settings.load(this);
        String sig = settingsSigOf(s);
        if (sig.equals(lastSig) && rootFrame != null) {
            set = s;                       // 没变：不重建，界面保持原样
            return;
        }
        lastSig = sig;
        set = s;
        th = Theme.of(set.theme);
        hasBg = Bg.exists(this);
        computeDayColors();
        buildUi();
        render();
    }

    private String settingsSigOf(Settings s) {
        StringBuilder sb = new StringBuilder();
        sb.append(s.theme).append('|').append(s.bgAlpha).append('|').append(s.bgBlur)
                .append('|').append(s.cardAlpha).append('|').append(s.corner)
                .append('|').append(s.anim).append('|').append(s.colorMode)
                .append('|').append(s.startDate).append('|').append(bgSig());
        for (Settings.Period p : s.periods) {
            sb.append(p.n).append(':').append(p.start).append('-').append(p.end).append(',');
        }
        for (Settings.Break b : s.breaks) sb.append(b.after).append(':').append(b.name)
                .append('@').append(b.start).append('-').append(b.end).append(',');
        return sb.toString();
    }

    /**
     * 背景图的「内容指纹」。
     *
     * 以前这里是 {@code Bg.exists(this)}（一个布尔值），于是已经有背景图时换图/重新裁切，
     * 这个值一直是 true → 签名不变 → onResume 走"没变"的快速返回 → 背景不刷新，
     * 表现为"改了背景图必须退出应用重进才生效"。
     * 现在把 生成计数器 + 三个背景文件的时间戳与大小 都算进去（计数器覆盖同进程内的改动，
     * 时间戳覆盖别处/重启前后的改动）。
     */
    private String bgSig() {
        StringBuilder sb = new StringBuilder();
        sb.append(Bg.generation());
        appendBgSig(sb, Bg.srcFile(this));
        appendBgSig(sb, Bg.cropFile(this, true));
        appendBgSig(sb, Bg.cropFile(this, false));
        return sb.toString();
    }

    private void appendBgSig(StringBuilder sb, File f) {
        sb.append('|').append(f.exists() ? f.lastModified() : 0L).append(':').append(f.length());
    }

    @Override
    public void onConfigurationChanged(Configuration cfg) {
        super.onConfigurationChanged(cfg);
        lastContentW = -1;
        lastContentH = -1;
        th = Theme.of(set.theme);
        hasBg = Bg.exists(this);
        computeDayColors();
        applyBackground();
        buildUi();
        render();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        Uri u = uriFrom(intent);
        if (u != null) importFrom(u);
    }

    private Uri uriFrom(Intent it) {
        if (it == null) return null;
        if (it.getData() != null) return it.getData();
        if (Intent.ACTION_SEND.equals(it.getAction())) {
            Object o = it.getParcelableExtra(Intent.EXTRA_STREAM);
            if (o instanceof Uri) return (Uri) o;
        }
        return null;
    }

    private int clampWeek(int w) { return Math.max(1, Math.min(w, maxWeek)); }

    /** 配色：始终「一门课一个颜色」；有背景图取色就用图里的糖果色盘，否则用主题色系生成的色盘 */
    private void computeDayColors() {
        courseColorIdx.clear();
        final int N = 12;
        int[] pal = new int[N];
        if (hasBg && set.colorMode == 1) {
            int[] fromBg = Theme.extractPalette(Bg.processed(this, 480, 0), N, th.seed);
            for (int i = 0; i < N; i++) pal[i] = fromBg[i % fromBg.length];
        } else {
            for (int i = 0; i < N; i++) {
                pal[i] = Theme.candy(Theme.hsvc(th.hue + i * 30f, 0.55f, 0.96f));
            }
        }
        palette = pal;

        // 课程名 → 色号：哈希定起点 + 最少使用优先，保证同课同色、不同课尽量不同色
        List<String> names = new ArrayList<String>();
        for (Course c : courses) if (!names.contains(c.name)) names.add(c.name);
        Collections.sort(names);
        int[] used = new int[N];
        for (String nm : names) {
            int start = Math.abs(nm.hashCode()) % N;
            int best = start, bestUse = Integer.MAX_VALUE;
            for (int k = 0; k < N; k++) {
                int idx = (start + k) % N;
                if (used[idx] < bestUse) {
                    bestUse = used[idx];
                    best = idx;
                    if (bestUse == 0) break;
                }
            }
            used[best]++;
            courseColorIdx.put(nm, best);
        }

        // 兜底色（表头等使用）
        for (int d = 1; d <= 7; d++) daySeed[d] = th.daySeed(d);
        daySeed[0] = th.seed;
        for (int d = 0; d <= 7; d++) {
            dayFill[d] = Theme.mix(daySeed[d], 0xFFFFFFFF, 0.58f);
            dayName[d] = Theme.shade(daySeed[d], 0.36f);
            dayRoom[d] = Theme.shade(daySeed[d], 0.55f);
            dayTeacher[d] = Theme.shade(daySeed[d], 0.68f);
        }
    }

    /** 一门课固定的一套颜色（底 / 课名 / 地点 / 教师） */
    private int[] colorsOf(Course c, int day) {
        if (set.colorMode == 0) {
            // 主题色单色模式：所有课都用主题色
            int fill = Theme.mix(th.primary, Color.WHITE, 0.76f);
            return new int[]{fill, Theme.shade(th.primary, 0.42f),
                    Theme.shade(th.primary, 0.58f), Theme.shade(th.primary, 0.72f)};
        }
        if (set.colorMode == 1 && palette != null && palette.length > 0) {
            Integer idx = courseColorIdx.get(c.name);
            int seed = palette[idx == null ? 0 : idx % palette.length];
            int fill = Theme.candy(seed);
            return new int[]{fill, Theme.shade(fill, 0.40f), Theme.shade(fill, 0.58f), Theme.shade(fill, 0.72f)};
        }
        return new int[]{dayFill[day], dayName[day], dayRoom[day], dayTeacher[day]};
    }

    // ---------------------------------------------------------------- 骨架

    private void buildUi() {
        rootFrame = new FrameLayout(this);
        rootFrame.setFitsSystemWindows(true);

        rootBox = new LinearLayout(this);
        rootBox.setOrientation(LinearLayout.VERTICAL);

        // ---- Top app bar
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(th.surface);
        bar.setPadding(dp(12), dp(8), dp(8), dp(8));

        ImageView mascot = new ImageView(this);
        mascot.setImageResource(R.drawable.ic_mascot);
        mascot.setScaleType(ImageView.ScaleType.CENTER_CROP);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(dp(34), dp(34));
        mlp.rightMargin = dp(2);
        bar.addView(mascot, mlp);

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = dp(10);
        titles.setLayoutParams(tlp);
        TextView t1 = new TextView(this);
        t1.setText("TY课程表");
        t1.setTextColor(th.onSurface);
        t1.setTextSize(19);
        t1.setTypeface(Typeface.DEFAULT_BOLD);
        headerSub = new TextView(this);
        headerSub.setText(semester.isEmpty() ? "导入 PDF 课表，按教学周查看" : semester);
        headerSub.setTextColor(th.onSurfaceVariant);
        headerSub.setTextSize(11.5f);
        titles.addView(t1);
        titles.addView(headerSub);
        bar.addView(titles);

        btnList = iconButton(R.drawable.ic_list, th.onSurfaceVariant, "全部课程 / 周课表", new View.OnClickListener() {
            public void onClick(View v) {
                showAll = !showAll;
                pop(v);
                render();
            }
        });
        bar.addView(btnList);
        bar.addView(iconButton(R.drawable.ic_add, th.primary, "手动加课", new View.OnClickListener() {
            public void onClick(View v) {
                pop(v);
                openManualAdd(v, -1, 0);   // 卡片里再选星期与节次
            }
        }));
        btnImport = iconButton(R.drawable.ic_import, th.primary, "导入课表（PDF / Excel）", new View.OnClickListener() {
            public void onClick(View v) {
                pop(v);
                pickPdf();
            }
        });
        bar.addView(btnImport);
        btnSettings = iconButton(R.drawable.ic_settings, th.onSurfaceVariant, "设置", new View.OnClickListener() {
            public void onClick(View v) {
                pop(v);
                openSettings(v);
            }
        });
        bar.addView(btnSettings);
        rootBox.addView(bar);

        // ---- 周次 chips
        HorizontalScrollView chipScroll = new HorizontalScrollView(this);
        chipScroll.setHorizontalScrollBarEnabled(false);
        chipScroll.setBackgroundColor(Theme.pct(th.surface, hasBg ? 55 : 100));
        weekBar = new LinearLayout(this);
        weekBar.setOrientation(LinearLayout.HORIZONTAL);
        weekBar.setPadding(dp(10), dp(6), dp(10), dp(8));
        chipScroll.addView(weekBar);
        rootBox.addView(chipScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        infoText = new TextView(this);
        infoText.setTextColor(th.onSurfaceVariant);
        infoText.setTextSize(11.5f);
        infoText.setPadding(dp(16), dp(6), dp(16), dp(2));
        rootBox.addView(infoText);

        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(10), dp(2), dp(10), dp(88));
        sv.addView(content);
        rootBox.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        rootFrame.addView(rootBox, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // ---- FAB：回到本周
        FrameLayout fab = new FrameLayout(this);
        fab.setBackground(rippleRounded(Theme.pct(th.primaryContainer, 70), chipRadius()));
        ImageView fabIcon = new ImageView(this);
        fabIcon.setImageResource(R.drawable.ic_today);
        fabIcon.setColorFilter(th.onPrimaryContainer);
        FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER);
        fab.addView(fabIcon, flp);
        FrameLayout.LayoutParams fabLp = new FrameLayout.LayoutParams(dp(56), dp(56),
                Gravity.BOTTOM | Gravity.END);
        fabLp.rightMargin = dp(18);
        fabLp.bottomMargin = dp(20);
        fab.setLayoutParams(fabLp);
        fab.setElevation(dp(6));
        fab.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                pop(v);
                showAll = false;
                currentWeek = clampWeek(set.weekOfToday());
                render();
            }
        });
        if (set.anim) {
            fab.setScaleX(0f); fab.setScaleY(0f);
            fab.animate().scaleX(1f).scaleY(1f).setDuration(220)
                    .setInterpolator(new DecelerateInterpolator()).start();
        }
        rootFrame.addView(fab);

        setContentView(rootFrame);
        Fonts.apply(rootFrame);
        applyBackground();
        applyListIcon();
    }

    private void applyListIcon() {
        if (btnList != null) btnList.setImageResource(showAll ? R.drawable.ic_grid : R.drawable.ic_list);
    }

    /** 背景图 + 不透明度（图会盖在 surface 上） */
    private void applyBackground() {
        if (rootBox == null) return;
        int base = ColorDrawable.class != null ? 0 : 0;
        if (!hasBg || set.bgAlpha <= 0) {
            rootBox.setBackgroundColor(th.surface);
            return;
        }
        float aspect = aspectOf();
        Bitmap bmp = Bg.background(this, 1400, set.bgBlur, aspect);
        if (bmp == null) {
            rootBox.setBackgroundColor(th.surface);
            return;
        }
        BitmapDrawable bd = new BitmapDrawable(getResources(), bmp);
        bd.setAlpha(Math.round(255f * Theme.clamp(set.bgAlpha, 0, 100) / 100f));
        rootBox.setBackground(new LayerDrawable(new Drawable[]{new ColorDrawable(th.surface), bd}));
    }

    /** 设置页：把按钮的屏幕位置传过去，由设置页自己演非线性展开动画（不依赖系统转场） */
    private void openSettings(View from) {
        Intent i = new Intent(this, SettingsActivity.class);
        int[] loc = new int[2];
        from.getLocationOnScreen(loc);
        i.putExtra("bx", loc[0]);
        i.putExtra("by", loc[1]);
        i.putExtra("bw", from.getWidth());
        i.putExtra("bh", from.getHeight());
        i.putExtra("anim", set.anim);
        startActivity(i);
        overridePendingTransition(0, 0);
    }

    /** 当前窗口宽高比（用于背景图不拉伸） */
    private float aspectOf() {
        int w = lastContentW > 0 ? lastContentW : getResources().getDisplayMetrics().widthPixels;
        int h = lastContentH > 0 ? lastContentH : getResources().getDisplayMetrics().heightPixels;
        if (w <= 0 || h <= 0) return 1.6f;
        return (float) w / h;
    }

    private void refreshWeekBar() {
        weekBar.removeAllViews();
        int todayWeek = set.weekOfToday();
        weekBar.addView(chip("全部", showAll, new View.OnClickListener() {
            public void onClick(View v) {
                showAll = true;
                pop(v);
                render();
            }
        }));
        for (int w = 1; w <= maxWeek; w++) {
            final int week = w;
            boolean isToday = w == todayWeek;
            weekBar.addView(chip((isToday ? "第" + w + "周 ·今天" : "第" + w + "周"),
                    !showAll && w == currentWeek, new View.OnClickListener() {
                        public void onClick(View v) {
                            showAll = false;
                            currentWeek = week;
                            pop(v);
                            render();
                        }
                    }));
        }
    }

    /** MD3 filter chip */
    private View chip(CharSequence text, boolean selected, View.OnClickListener l) {
        LinearLayout chip = new LinearLayout(this);
        chip.setOrientation(LinearLayout.HORIZONTAL);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setPadding(dp(12), dp(6), dp(14), dp(6));
        int bg = selected ? Theme.pct(th.secondaryContainer, hasBg ? 82 : 100)
                : Theme.pct(th.surface, hasBg ? 58 : 100);
        chip.setBackground(ripple(Theme.pct(selected ? th.onSecondaryContainer : th.primary, 16),
                bg, 10, selected ? 0 : dp(1), th.outlineVariant));
        if (selected) {
            ImageView ic = new ImageView(this);
            ic.setImageResource(R.drawable.ic_check);
            ic.setColorFilter(th.onSecondaryContainer);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(16), dp(16));
            ilp.rightMargin = dp(6);
            chip.addView(ic, ilp);
        }
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(13.5f);
        tv.setTextColor(selected ? th.onSecondaryContainer : th.onSurfaceVariant);
        tv.setTypeface(selected ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        chip.addView(tv);
        chip.setOnClickListener(l);
        chip.setClickable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(8);
        chip.setLayoutParams(lp);
        return chip;
    }

    // ---------------------------------------------------------------- 渲染

    private void render() {
        refreshWeekBar();
        applyListIcon();
        content.removeAllViews();
        if (courses.isEmpty()) {
            infoText.setText("还没有数据");
            content.addView(emptyView());
            animateIn(content);
            return;
        }
        if (showAll) {
            renderAllList();
        } else {
            renderWeekGrid(currentWeek);
        }
        Fonts.apply(content);
        if (infoText != null) Fonts.apply(infoText);
        animateIn(content);
        // 布局完成后按真实宽度重排一次，适配不同屏幕/分屏
        content.post(new Runnable() {
            public void run() {
                int w = content.getWidth();
                int h = content.getHeight();
                if (w > 0 && Math.abs(w - lastContentW) > 2) {
                    lastContentW = w;
                    lastContentH = h;
                    applyBackground();      // 比例变了，背景重新按新比例取图
                    render();
                } else if (h > 0) {
                    lastContentH = h;
                }
            }
        });
    }

    private void animateIn(View v) {
        if (!set.anim) return;
        v.setAlpha(0f);
        v.setTranslationY(dp(14));
        v.animate().alpha(1f).translationY(0f).setDuration(190)
                .setInterpolator(new DecelerateInterpolator()).start();
    }

    private void pop(View v) {
        if (!set.anim) return;
        v.animate().scaleX(0.9f).scaleY(0.9f).setDuration(70)
                .withEndAction(new Runnable() {
                    public void run() { }
                }).start();
        v.postDelayed(new Runnable() {
            public void run() { v.animate().scaleX(1f).scaleY(1f).setDuration(130).start(); }
        }, 70);
    }

    // -------- 表格

    private static class Row {
        int period;        // >0 表示这是一节课的行
        int afterPeriod;   // 休息行：它下面接着的那一节的节次
        int anchor;        // 休息行：它上面那一节的节次（用来算这一行归哪一段）
        String label;
        String range;      // 休息段自己的时段文字
    }

    /**
     * 课表的行顺序一律按时间排：一节课和一段休息都是时间轴上的区间，谁先开始谁在上面。
     * 这样在设置里改了某节课的时间，课表里的位置会自己跟着变。
     */
    private List<Row> buildRows(List<Course> weekList) {
        TreeSet<Integer> nums = new TreeSet<Integer>();
        for (Settings.Period p : set.periods) nums.add(p.n);
        for (Course c : weekList) {
            if (c.startPeriod > 0) {
                int e = Math.max(c.endPeriod, c.startPeriod);
                for (int n = c.startPeriod; n <= Math.min(e, MAX_PERIOD); n++) nums.add(n);
            }
        }
        final List<Settings.Break> brks = new ArrayList<Settings.Break>(set.breaks);
        java.util.Collections.sort(brks, new java.util.Comparator<Settings.Break>() {
            public int compare(Settings.Break a, Settings.Break b) {
                // 跟设置页同一套：拖过顺序按手动顺序，没拖过按时间
                return set.compareRows(a, b);
            }
        });
        List<Row> rows = new ArrayList<Row>();
        int bi = 0;
        int prev = 0;                       // 上一节课的节次（休息行的 anchor）
        for (int n : nums) {
            Row r = new Row();
            r.period = n;
            Settings.Period p = set.period(n);
            if (p != null) {
                // 在这一节开始之前就开始的休息段：排在它上面
                while (bi < brks.size() && set.breakRange(brks.get(bi))[0] < p.start) {
                    rows.add(breakRowOf(brks.get(bi++), prev, n));
                }
            }
            rows.add(r);
            if (p != null) {
                // 落在这节时段里的休息段：排在它下面
                while (bi < brks.size() && set.breakRange(brks.get(bi))[0] < p.end) {
                    rows.add(breakRowOf(brks.get(bi++), n, 0));
                }
                prev = n;
            }
        }
        while (bi < brks.size()) rows.add(breakRowOf(brks.get(bi++), prev, 0));
        return rows;
    }

    /** afterPeriod = 排在这一行下面的那一节（没有就是 0）；anchor = 排在这一行上面那一节 */
    private Row breakRowOf(Settings.Break b, int anchorPeriod, int afterPeriod) {
        Row s = new Row();
        s.afterPeriod = afterPeriod;
        s.anchor = anchorPeriod;
        s.label = b.name == null || b.name.trim().isEmpty() ? "休息" : b.name;
        s.range = set.breakTimeText(b);
        return s;
    }

    private int periodCount(List<Row> rows) {
        int c = 0;
        for (Row r : rows) if (r.period > 0) c++;
        return c;
    }

    /** 行高：根据可用高度自适应，保证竖屏/横屏/分屏都能看到完整的表 */
    private int rowH(int periods) {
        int avail = lastContentH > 0 ? lastContentH : getResources().getDisplayMetrics().heightPixels * 2 / 3;
        int h = (int) ((avail - dp(46) - dp(20) * 2f) / Math.max(1, periods + 1.2f));
        return Theme.clamp(h, dp(30), dp(56));
    }

    private int secH() { return dp(18); }
    private int timeW() { return dp(62); }

    private int dayColWidth() {
        int avail = lastContentW > 0 ? lastContentW : getResources().getDisplayMetrics().widthPixels;
        int w = (avail - timeW() - dp(2)) / 7;
        return Math.max(dp(40), w);
    }

    private void renderWeekGrid(int week) {
        List<Course> weekList = new ArrayList<Course>();
        for (Course c : courses) if (!c.isOther() && c.weeks.contains(week)) weekList.add(c);
        List<Course> otherList = new ArrayList<Course>();
        for (Course c : courses) if (c.isOther() && c.weeks.contains(week)) otherList.add(c);

        List<Row> rows = buildRows(weekList);
        int periods = periodCount(rows);
        int sections = rows.size() - periods;

        // ---- 宽度：永远铺满可用宽度，不做左右滚动
        int pad = content.getPaddingLeft() + content.getPaddingRight();
        int availW = lastContentW > 0 ? (lastContentW - pad)
                : getResources().getDisplayMetrics().widthPixels - dp(20);
        int tw = Theme.clamp(Math.round(availW * 0.092f), dp(38), dp(74));
        int rest = availW - tw;
        int dw = Math.max(1, rest / 7);
        int rem = rest - dw * 7;                 // 余数分给前几列，保证总宽正好等于可用宽度
        int maxDw = dp(178);
        boolean centered = false;
        if (dw > maxDw) { dw = maxDw; rem = 0; centered = true; }
        int tableW = centered ? (tw + dw * 7) : availW;

        // ---- 行高：按可用高度自适应（放不下就上下滚动）
        int availH = lastContentH > 0 ? lastContentH
                : getResources().getDisplayMetrics().heightPixels / 2;
        int rh = Theme.clamp((availH - dp(42) - sections * secH()) / Math.max(1, periods),
                dp(30), dp(80));

        List<List<Course>> byDay = new ArrayList<List<Course>>();
        for (int d = 0; d <= 7; d++) byDay.add(new ArrayList<Course>());
        for (Course c : weekList) if (c.day >= 1 && c.day <= 7) byDay.get(c.day).add(c);

        long wstart = set.weekStartMillis();
        int todayWeek = set.weekOfToday();
        int todayDow = Settings.todayDow();
        boolean showToday = (todayWeek == week);

        // ---- 整张表：圆角外框 + 裁剪，内部格子连成一片
        LinearLayout table = new LinearLayout(this);
        table.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable tb = new GradientDrawable();
        tb.setColor(hasBg ? Theme.pct(th.surface, 14) : Color.TRANSPARENT);
        tb.setCornerRadius(dp(set.corner));
        tb.setStroke(Math.max(1, dp(0.6f)), Theme.pct(th.outline, hasBg ? 130 : 210));
        table.setBackground(tb);
        table.setClipToOutline(true);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(42)));
        head.addView(headCell("节次\n时间", tw, th.onSurfaceVariant));
        for (int d = 1; d <= 7; d++) {
            boolean isToday = showToday && d == todayDow;
            String date = Settings.dateOfWeek(wstart, week, d);
            int wcol = dw + (d <= rem ? 1 : 0);
            head.addView(dayHeadCell(isToday ? "今天\n" + date : DAY_NAME[d] + "\n" + date,
                    wcol, isToday, d));
        }
        table.addView(head);

        LinearLayout bodyRow = new LinearLayout(this);
        bodyRow.setOrientation(LinearLayout.HORIZONTAL);
        bodyRow.addView(buildTimeColumn(rows, tw, rh));
        LinearLayout dayCols = new LinearLayout(this);
        dayCols.setOrientation(LinearLayout.HORIZONTAL);
        for (int d = 1; d <= 7; d++) {
            int wcol = dw + (d <= rem ? 1 : 0);
            dayCols.addView(buildDayColumn(d, byDay.get(d), rows, wcol, rh, showToday && d == todayDow));
        }
        bodyRow.addView(dayCols);
        table.addView(bodyRow);

        if (centered) {
            FrameLayout wrap = new FrameLayout(this);
            wrap.addView(table, new FrameLayout.LayoutParams(tableW, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER_HORIZONTAL));
            content.addView(wrap, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        } else {
            content.addView(table, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        String range = Settings.dateOfWeek(wstart, week, 1) + " ~ " + Settings.dateOfWeek(wstart, week, 7);
        infoText.setText("第 " + week + " 周（" + range + "）· " + weekList.size() + " 节安排"
                + (showToday ? " · 今天" + DAY_SHORT[todayDow] : "")
                + (sourceName.isEmpty() ? "" : " · " + sourceName));

        if (!otherList.isEmpty()) {
            content.addView(sectionLabel("其他课程（无固定时间）"));
            for (Course c : otherList) content.addView(courseCard(c, false));
        }
    }

    /** 表格线（1px），相邻格子共用 */
    private GradientDrawable gridBg(int fill, int lineColor) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(0);
        g.setStroke(Math.max(1, dp(0.6f)), lineColor);
        return g;
    }

    /** 午休/晚饭的底色：与主题色一致 */
    private int breakColor() { return Theme.mix(th.primary, Color.WHITE, 0.74f); }

    private int gridLine() { return Theme.pct(th.primary, hasBg ? 120 : 150); }

    private View headCell(String text, int w, int color) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(color);
        tv.setTextSize(10f);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setGravity(Gravity.CENTER);
        tv.setBackground(gridBg(Theme.pct(th.surfaceContainer, hasBg ? 88 : 100), gridLine()));
        tv.setPadding(0, dp(5), 0, dp(5));
        tv.setLineSpacing(0, 1.1f);
        tv.setLayoutParams(new LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.MATCH_PARENT));
        return tv;
    }

    private View dayHeadCell(String text, int w, boolean isToday, int day) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(isToday ? th.onPrimary : th.onSurfaceVariant);
        tv.setTextSize(10f);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setGravity(Gravity.CENTER);
        tv.setBackground(gridBg(isToday ? Theme.pct(th.primary, hasBg ? 94 : 100)
                : Theme.pct(th.surfaceContainer, hasBg ? 88 : 100), gridLine()));
        tv.setPadding(dp(1), dp(5), dp(1), dp(5));
        tv.setLineSpacing(0, 1.1f);
        tv.setLayoutParams(new LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.MATCH_PARENT));
        return tv;
    }

    /** 第一列：节次 + 时间，格子之间连起来（只有整张表的外框是圆角） */

    private View buildTimeColumn(List<Row> rows, int tw, int rh) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(tw, ViewGroup.LayoutParams.WRAP_CONTENT));
        for (Row r : rows) {
            if (r.period > 0) {
                LinearLayout cell = new LinearLayout(this);
                cell.setOrientation(LinearLayout.VERTICAL);
                cell.setGravity(Gravity.CENTER);
                cell.setBackground(gridBg(Theme.pct(th.surfaceContainerLow, hasBg ? 78 : 100), gridLine()));
                cell.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rh));
                TextView no = new TextView(this);
                no.setText(String.valueOf(r.period));
                no.setTextColor(th.onSurface);
                no.setTextSize(rh > dp(52) ? 13 : 11.5f);
                no.setTypeface(Typeface.DEFAULT_BOLD);
                no.setGravity(Gravity.CENTER);
                cell.addView(no);
                String t = set.timeText(r.period);
                if (!t.isEmpty() && rh >= dp(32)) {
                    TextView tm = new TextView(this);
                    tm.setText(t);
                    tm.setTextColor(th.onSurfaceVariant);
                    tm.setTextSize(rh > dp(52) ? 9.5f : 8f);
                    tm.setGravity(Gravity.CENTER);
                    cell.addView(tm);
                }
                col.addView(cell);
            } else {
                TextView s = new TextView(this);
                String bt = r.range == null ? "" : r.range;
                s.setText(bt.isEmpty() ? r.label : (r.label + " " + bt));
                s.setTextColor(Theme.shade(th.primary, 0.5f));
                s.setTextSize(8f);
                s.setTypeface(Typeface.DEFAULT_BOLD);
                s.setGravity(Gravity.CENTER);
                s.setBackground(gridBg(Theme.pct(breakColor(), hasBg ? 74 : 100), gridLine()));
                s.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, secH()));
                col.addView(s);
            }
        }
        return col;
    }

    private View buildDayColumn(int day, List<Course> dayCourses, List<Row> rows, int dw, int rh,
                                boolean isToday) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(dw, ViewGroup.LayoutParams.WRAP_CONTENT));

        for (Course c : dayCourses) {
        }
        boolean[] covered = new boolean[MAX_PERIOD + 2];
        int y = 0;
        int n = rows.size();
        for (int i = 0; i < n; i++) {
            Row r = rows.get(i);
            if (r.period == 0) {
                View sp = new View(this);
                sp.setBackgroundColor(Theme.pct(breakColor(), hasBg ? 56 : 88));
                sp.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, secH()));
                y += secH();
                col.addView(sp);
                continue;
            }
            int pr = r.period;
            // 这一节如果已经被前面某个跨多节的格子吃掉了，就什么都不要加。
            // 以前只是 continue 掉这一次迭代，但接下来那些同样被覆盖的节次还会再走一遍
            //「找课 → 加格子」，于是同一个大格子被重复加进这一列，这一列就比别人高一截 ——
            // 各列高度一旦不同，整块课表就上下错位。
            if (covered[pr]) continue;
            List<Course> at = coursesCovering(dayCourses, pr);
            if (at.isEmpty()) {
                // 没课的格子：完全透明、没有格线，只占位。
                // 高度必须和时间列用同一个 rh（以前写死 40dp，行高放大后每个空格子都短一截，
                // 这一列后面的内容就整体往上错位）
                View empty = new View(this);
                // 空白格子本身是透明的；这里给一个很淡的按压高亮，否则长按了完全看不出反馈
                empty.setBackground(ripple(Theme.pct(th.primary, 26), Color.TRANSPARENT, dp(6), 0, 0));
                empty.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rh));
                empty.setClickable(true);
                empty.setLongClickable(true);
                empty.setFocusable(false);
                // 两种手势都能加课：长按，或双击（双击更好按，不容易和拖动/看详情混）
                final long[] lastTap = new long[]{0};
                empty.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        long now = System.currentTimeMillis();
                        if (now < swallowClicksUntil) return;
                        if (now - lastTap[0] < 400) {
                            lastTap[0] = 0;
                            openManualAdd(v, day, pr);
                        } else {
                            lastTap[0] = now;
                        }
                    }
                });
                empty.setOnLongClickListener(new View.OnLongClickListener() {
                    public boolean onLongClick(View v) {
                        try { v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS); } catch (Throwable ignore) {}
                        openManualAdd(v, day, pr);   // 必须传来源，否则展开动画会从整屏开始
                        return true;
                    }
                });
                y += rh;
                col.addView(empty);
                continue;
            }
            // 只把「同一门课占的多个节次」合成一格（含同一时段的冲突课）
            int st = pr, en = pr;
            for (Course c : at) en = Math.max(en, clampPeriod(Math.max(c.endPeriod, c.startPeriod)));
            boolean grew = true;
            while (grew) {
                grew = false;
                for (Course c : dayCourses) {
                    if (c.startPeriod <= 0) continue;
                    int cs = Math.max(1, c.startPeriod);
                    int ce = clampPeriod(Math.max(c.endPeriod, c.startPeriod));
                    if (cs <= en && ce > en) { en = ce; grew = true; }
                    if (cs < st && ce >= st) { st = cs; grew = true; }
                }
            }
            for (int k = st; k <= en && k <= MAX_PERIOD; k++) covered[k] = true;

            // 这个格子占的高度 = 行列表里「第 st 节那一行」到「第 en 节那一行」之间所有行的和，
            // 中间夹着的休息行也要算进去。
            // 以前是按「休息的 anchor 落在 st..en 之间」判的，但行在列表里的实际位置和 anchor
            // 并不总是一致（休息排在它 anchor 那一节的下一行），于是有的列少算 45、有的列多算 45，
            // 各列高度对不上，整块课就往下/往上错位。
            int firstRow = rowIndexOfPeriod(rows, st);
            int lastRow = rowIndexOfPeriod(rows, en);
            int h = 0;
            for (int ri = firstRow; ri >= 0 && ri <= lastRow && ri < rows.size(); ri++) {
                h += rows.get(ri).period > 0 ? rh : secH();
            }
            y += h;
            col.addView(courseCell(at, dw, h, day));
        }
        return col;
    }

    private int clampPeriod(int p) { return Math.max(1, Math.min(MAX_PERIOD, p)); }

    /** 第 n 节在行列表里的下标；没有就返回 -1 */
    private int rowIndexOfPeriod(List<Row> rows, int n) {
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i).period == n) return i;
        }
        return -1;
    }


    private List<Course> coursesCovering(List<Course> list, int p) {
        List<Course> out = new ArrayList<Course>();
        for (Course c : list) {
            if (c.startPeriod <= 0) continue;
            int s = Math.max(1, c.startPeriod);
            int e = clampPeriod(Math.max(c.endPeriod, c.startPeriod));
            if (s <= p && p <= e) out.add(c);
        }
        return out;
    }

    private View courseCell(final List<Course> cs, int w, int h, int day) {
        final Course first = cs.get(0);
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setGravity(Gravity.CENTER);
        v.setPadding(dp(3), dp(3), dp(3), dp(3));
        final int[] col4 = colorsOf(first, day);
        int fillAlpha = hasBg ? Theme.clamp(set.cardAlpha + 22, 34, 100) : 100;
        int radius = Math.min(set.corner, Math.min(h, w) / 2);
        v.setBackground(ripple(Theme.pct(col4[1], 18), Theme.pct(col4[0], fillAlpha), radius,
                Math.max(1, dp(0.6f)), Theme.pct(th.primary, 150)));
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h));
        v.setClickable(true);
        v.setOnClickListener(new View.OnClickListener() {
            public void onClick(View x) { openDetail(cs, x); }
        });
        v.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View x) { openRaw(first, x); return true; }
        });

        float dens = getResources().getDisplayMetrics().density;
        float wDp = w / dens;
        int per = h / Math.max(1, cs.size());
        float perDp = per / dens;
        float nameSp = Theme.clamp(Math.min(wDp * 0.185f, perDp * 0.42f), 8f, 19f);
        float roomSp = nameSp * 0.80f;
        float teacherSp = nameSp * 0.72f;
        int needName = (int) (nameSp * dens * 1.3f) + dp(2);
        int needRoom = needName + (int) (roomSp * dens * 1.3f);
        int needTeacher = needRoom + (int) (teacherSp * dens * 1.3f);

        for (int i = 0; i < cs.size(); i++) {
            Course c = cs.get(i);
            if (i > 0) {
                View gap = new View(this);
                gap.setLayoutParams(new LinearLayout.LayoutParams(1, dp(5)));
                v.addView(gap);
            }
            TextView name = new TextView(this);
            name.setText(c.name.isEmpty() ? "未识别课程" : c.name);
            name.setTextColor(col4[1]);
            name.setTextSize(nameSp);
            name.setTypeface(Typeface.DEFAULT_BOLD);
            name.setGravity(Gravity.CENTER);
            name.setMaxLines(2);
            name.setEllipsize(TextUtils.TruncateAt.END);
            v.addView(name);

            if (!c.room.isEmpty() && per >= needRoom) {
                TextView room = new TextView(this);
                room.setText(c.room);
                room.setTextColor(col4[2]);
                room.setTextSize(roomSp);
                room.setGravity(Gravity.CENTER);
                room.setMaxLines(1);
                room.setEllipsize(TextUtils.TruncateAt.END);
                v.addView(room);
            }
            if (!c.teacher.isEmpty() && per >= needTeacher) {
                TextView teacher = new TextView(this);
                teacher.setText(c.teacher);
                teacher.setTextColor(col4[3]);
                teacher.setTextSize(teacherSp);
                teacher.setGravity(Gravity.CENTER);
                teacher.setMaxLines(1);
                teacher.setEllipsize(TextUtils.TruncateAt.END);
                v.addView(teacher);
            }
        }
        return v;
    }

    private String weekCellSub(Course c, int count) {
        if (count > 1) return "共 " + count + " 门";
        StringBuilder sb = new StringBuilder();
        if (!c.room.isEmpty()) sb.append(c.room);
        if (!c.teacher.isEmpty()) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(c.teacher);
        }
        return sb.toString();
    }

    private View sectionLabel(String s) {
        TextView h = new TextView(this);
        h.setText(s);
        h.setTextColor(th.onSurfaceVariant);
        h.setTextSize(12.5f);
        h.setTypeface(Typeface.DEFAULT_BOLD);
        h.setPadding(dp(6), dp(16), 0, dp(8));
        return h;
    }

    // -------- 全部课程

    private void renderAllList() {
        int total = 0;
        for (Course c : courses) if (!c.isOther()) total++;
        infoText.setText("全部课程 · " + total + " 门"
                + (sourceName.isEmpty() ? "" : " · " + sourceName));
        List<Course> list = new ArrayList<Course>(courses);
        Collections.sort(list, new Comparator<Course>() {
            public int compare(Course a, Course b) {
                if (a.day != b.day) return a.day - b.day;
                if (a.startPeriod != b.startPeriod) return a.startPeriod - b.startPeriod;
                return a.name.compareTo(b.name);
            }
        });
        for (Course c : list) content.addView(courseCard(c, true));
    }

    private View courseCard(final Course c, boolean showDay) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(12), dp(12), dp(12), dp(12));
        card.setBackground(ripple(Theme.pct(th.primary, 14),
                Theme.pct(th.surfaceContainerLow, hasBg ? 80 : 100), set.corner + 4, 0, 0));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.bottomMargin = dp(8);
        card.setLayoutParams(clp);
        card.setElevation(dp(0.5f));
        card.setClickable(true);
        card.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                List<Course> one = new ArrayList<Course>();
                one.add(c);
                openDetail(one, v);
            }
        });
        card.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) {
                List<Course> one = new ArrayList<Course>();
                one.add(c);
                openRaw(c, v);
                return true;
            }
        });

        int day = c.day == 0 ? 0 : c.day;

        // 左侧：圆形日徽
        FrameLayout badge = new FrameLayout(this);
        GradientDrawable bgb = new GradientDrawable();
        bgb.setShape(GradientDrawable.OVAL);
        bgb.setColor(Theme.pct(th.dayContainer[day], hasMinBg() ? 78 : 100));
        badge.setBackground(bgb);
        TextView bt = new TextView(this);
        bt.setText(showDay ? DAY_SHORT[day] : periodLabel(c));
        bt.setTextColor(th.onDay[day]);
        bt.setTextSize(showDay ? 10f : 9f);
        bt.setTypeface(Typeface.DEFAULT_BOLD);
        bt.setGravity(Gravity.CENTER);
        badge.addView(bt, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(dp(46), dp(46));
        blp.rightMargin = dp(12);
        card.addView(badge, blp);

        LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.VERTICAL);
        mid.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView name = new TextView(this);
        name.setText(c.name.isEmpty() ? "（未识别课程名）" : c.name);
        name.setTextColor(th.onSurface);
        name.setTextSize(15.5f);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        mid.addView(name);

        String line1 = join(new String[]{showDay ? DAY_SHORT[day] + " " + periodLabel(c) : periodLabel(c),
                c.teacher, c.room});
        TextView sub = new TextView(this);
        sub.setText(line1.isEmpty() ? "教师 / 地点：未识别" : line1);
        sub.setTextColor(th.onSurfaceVariant);
        sub.setTextSize(12f);
        sub.setPadding(0, dp(3), 0, 0);
        mid.addView(sub);

        String line2 = weekLine(c);
        if (!line2.isEmpty()) {
            TextView w = new TextView(this);
            w.setText(line2);
            w.setTextColor(th.onSecondaryContainer);
            w.setTextSize(11.5f);
            w.setPadding(0, dp(4), 0, 0);
            mid.addView(w);
        }
        card.addView(mid);
        return card;
    }

    private boolean hasMinBg() { return hasBg; }

    private View emptyView() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(dp(24), dp(48), dp(24), dp(24));

        ImageView mascot = new ImageView(this);
        mascot.setImageResource(R.drawable.ic_mascot);
        mascot.setScaleType(ImageView.ScaleType.CENTER_CROP);
        box.addView(mascot, new LinearLayout.LayoutParams(dp(96), dp(96)));

        TextView t = new TextView(this);
        t.setText("还没有导入课表");
        t.setTextColor(th.onSurface);
        t.setTextSize(17);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(14), 0, 0);
        box.addView(t);

        TextView s = new TextView(this);
        s.setText("支持教务系统导出的课表：PDF / Excel(.xls/.xlsx) / HTML 表格\n点下面的按钮选文件，或用「打开方式 / 分享到」发给本应用");
        s.setTextColor(th.onSurfaceVariant);
        s.setTextSize(12.5f);
        s.setGravity(Gravity.CENTER);
        s.setPadding(0, dp(8), 0, dp(22));
        box.addView(s);

        box.addView(filledButton(R.drawable.ic_import, "导入课表（PDF / Excel）", new View.OnClickListener() {
            public void onClick(View v) { pickPdf(); }
        }));
        return box;
    }

    // ---------------------------------------------------------------- 文字

    private String periodLabel(Course c) {
        if (c.startPeriod <= 0) return c.isOther() ? "—" : "?";
        if (c.endPeriod <= c.startPeriod) return c.startPeriod + "节";
        return c.startPeriod + "-" + c.endPeriod + "节";
    }

    private String weekLine(Course c) {
        String s = c.weeks.isEmpty() ? "" : ("第 " + c.weeksSummary() + " 周");
        if (s.isEmpty() && !c.weeksText.isEmpty()) s = c.weeksText + "周";
        if (c.isOther()) s = s.isEmpty() ? "时间待定" : s + "（时间待定）";
        return s;
    }

    private String join(String[] parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p == null || p.trim().isEmpty()) continue;
            if (sb.length() > 0) sb.append(" · ");
            sb.append(p.trim());
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- 详情

    /** 圆角半径：全应用统一用课程表那个设置值 */
    private int chipRadius() { return Theme.clamp(set.corner, 4, 40); }

    private Drawable rippleRounded(int rippleColor, int radiusDp) {
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(dp(radiusDp));
        return new RippleDrawable(ColorStateList.valueOf(rippleColor), null, mask);
    }

    // ------------------------------------------------ 点击课程：格子展开成详情卡片

    private void openDetail(List<Course> cs, View src) {
        openCardFrom(src, buildDetailCard(cs));
    }

    /** 长按：显示 PDF 原文（同样用非线性展开动画） */
    private void openRaw(Course c, View src) {
        openCardFrom(src, buildRawCard(c));
    }

    private void openCardFrom(View src, LinearLayout card) {
        if (rootFrame == null) return;
        if (closingCard) {
            // 上一张卡片还在收：直接收干净，避免「刚关完就点不开」
            forceCloseDetail();
        }
        if (detailOpen || detailCard != null) {
            // 已经开着一张卡片（比如在详情卡片里点了「删除」要弹结果卡片）：
            // 直接返回会把新内容丢掉 —— 那就是「动画没了 / 点不动」。
            // 这里先把旧卡片干净收掉，再让新卡片从它的来源位置展开。
            forceCloseDetail();
        }
        detailOpen = true;
        detailSource = src;

        // 来源矩形：只接受「像某个控件」的矩形。
        // 任何整屏级别、或者拿不到位置的情况，一律降级成一个小锚点放在屏幕中上部 ——
        // 宁可退化成「从小块展开」，也绝不出现「从全屏缩小」。
        RectF sr = rectInRoot(src);
        if (rootFrame != null && sr != null) {
            float rw = rootFrame.getWidth(), rh = rootFrame.getHeight();
            if (rw > 0 && rh > 0
                    && (sr.width() > rw * 0.7f || sr.height() > rh * 0.7f || sr.width() < 2 || sr.height() < 2)) {
                sr = null;
            }
        }
        detailSourceRect = (sr != null) ? sr : fallbackAnchor();
        final RectF from = new RectF(detailSourceRect);
        dimOverlay = new View(this);
        dimOverlay.setBackgroundColor(0x00000000);
        dimOverlay.setClickable(true);
        dimOverlay.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { closeDetail(); }
        });
        rootFrame.addView(dimOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        detailCard = card;

        // 先把「终点」量出来，再让卡片以「起点尺寸」入场 —— 这样它出现在屏幕上的第一帧
        // 就一定是格子/按钮那么大，不会先以最终尺寸闪一帧（那看起来就像「从整屏缩下来」）。
        int rootW = Math.max(1, rootFrame.getWidth());
        int rootH = Math.max(1, rootFrame.getHeight());
        int tw = Math.min(rootW - dp(36), dp(540));
        card.measure(View.MeasureSpec.makeMeasureSpec(tw, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int thh = Math.min(card.getMeasuredHeight(), rootH - dp(56));
        final RectF to = new RectF((rootW - tw) / 2f, (rootH - thh) / 2f,
                (rootW - tw) / 2f + tw, (rootH - thh) / 2f + thh);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                Math.max(1, Math.round(from.width())), Math.max(1, Math.round(from.height())));
        lp.leftMargin = Math.round(from.left);
        lp.topMargin = Math.round(from.top);
        card.setLayoutParams(lp);
        card.setAlpha(1f);                 // 兜底可见；展开动画会把它从 0 淡进来
        rootFrame.addView(card);

        card.post(new Runnable() {
            public void run() { animateCard(from, to, true, null); }
        });
    }

    /** 控件在 rootFrame 坐标系里的矩形；控件已脱离视图树时返回 null */
    private RectF rectInRoot(View v) {
        if (v == null || rootFrame == null || !v.isAttachedToWindow()) return null;
        int[] vl = new int[2], rl = new int[2];
        v.getLocationInWindow(vl);
        rootFrame.getLocationInWindow(rl);
        int w = Math.max(1, v.getWidth()), h = Math.max(1, v.getHeight());
        return new RectF(vl[0] - rl[0], vl[1] - rl[1], vl[0] - rl[0] + w, vl[1] - rl[1] + h);
    }

    private boolean closingCard;
    private boolean cardFresh;   // 这张卡片是不是「刚重新展开」（而不是在已开卡片里换内容）
    private long swallowClicksUntil;   // 这段时间内的点击忽略（防长按抬手点穿），不动 detailOpen

    /** 万一动画回调没回来，兜底把卡片摘掉，避免「关不掉」 */
    private void forceCloseDetail() {
        try {
            if (detailCard != null && detailCard.getParent() != null) {
                ((ViewGroup) detailCard.getParent()).removeView(detailCard);
            }
            if (dimOverlay != null && dimOverlay.getParent() != null) {
                ((ViewGroup) dimOverlay.getParent()).removeView(dimOverlay);
            }
            if (detailSource != null && detailSource != rootFrame) detailSource.setAlpha(1f);
        } catch (Throwable ignore) {}
        detailCard = null;
        dimOverlay = null;
        detailSource = null;
        detailSourceRect = null;
        detailOpen = false;
        closingCard = false;
    }

    private void closeDetail() {
        // 兜底日志：先记一行，这样即使后面被哪个分支挡掉，日志里也能看出来
        if (closingCard) {
            // 上一次没收干净（锁没放开）：直接把残留收掉并放锁，而不是永远拒绝关闭。
            // 这就是「退出动画一直没有 / 关不掉」的根因所在。
            forceCloseDetail();
            return;
        }
        if (!detailOpen || detailCard == null) {
            if (detailCard != null) forceCloseDetail();
            else forceCloseDetail();     // 保证任何残留状态都被清掉
            return;
        }
        closingCard = true;
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            public void run() {
                // 动画还没把它收掉就强制收尾（避免「关不掉」）；
                // 注意这里必须无条件检查 closingCard，收掉了也要把锁放开，
                // 否则下一次关闭会被 if (closingCard) return 挡掉 —— 就是「退出键只能点一次」。
                if (closingCard) forceCloseDetail();
            }
        }, 700);
        // 从哪来回哪去：格子还在就用当前矩形，格子被重建过就用打开时记下的矩形
        RectF to = rectInRoot(detailSource);
        if (to == null) to = detailSourceRect;
        if (to == null) {
            to = new RectF(detailCard.getLeft(), detailCard.getTop(),
                    detailCard.getLeft() + detailCard.getWidth(),
                    detailCard.getTop() + detailCard.getHeight());
        }
        if (rootFrame != null && to != null) {
            float rw = rootFrame.getWidth(), rh = rootFrame.getHeight();
            if (rw > 0 && rh > 0 && (to.width() > rw * 0.7f || to.height() > rh * 0.7f)) {
                to = fallbackAnchor();      // 收回目标也不能是整屏
            }
        }
        to = clampToRoot(to);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) detailCard.getLayoutParams();
        final RectF from = new RectF(lp.leftMargin, lp.topMargin,
                lp.leftMargin + lp.width, lp.topMargin + lp.height);
        animateCard(from, to, false, new Runnable() {
            public void run() {
                if (detailCard != null && detailCard.getParent() != null) {
                    ((ViewGroup) detailCard.getParent()).removeView(detailCard);
                }
                if (dimOverlay != null && dimOverlay.getParent() != null) {
                    ((ViewGroup) dimOverlay.getParent()).removeView(dimOverlay);
                }
                if (detailSource != null && detailSource != rootFrame) {
                    detailSource.setAlpha(1f);
                }
                forceCloseDetail();      // 统一由它清干净并放开 closingCard
            }
        });
    }

    /** 来源不明确时用的小锚点：屏幕水平居中、偏上一点的一个小方块 */
    private RectF fallbackAnchor() {
        float w = rootFrame != null ? Math.max(1, rootFrame.getWidth()) : dp(360);
        float h = rootFrame != null ? Math.max(1, rootFrame.getHeight()) : dp(640);
        float a = dp(56);
        float cx = w / 2f, cy = h * 0.38f;
        return new RectF(cx - a / 2f, cy - a / 2f, cx + a / 2f, cy + a / 2f);
    }

    private RectF clampToRoot(RectF r) {
        float w = Math.max(1, rootFrame.getWidth()), h = Math.max(1, rootFrame.getHeight());
        float cx = Theme.clamp(r.centerX(), 0, w);
        float cy = Theme.clamp(r.centerY(), 0, h);
        float hw = Math.min(r.width(), w) / 2f, hh = Math.min(r.height(), h) / 2f;
        return new RectF(cx - hw, cy - hh, cx + hw, cy + hh);
    }

    /** 非线性（emphasized）曲线 + 内容错峰淡入 */
    private void animateCard(final RectF from, final RectF to, final boolean expand, final Runnable end) {
        if (detailCard == null) {
            return;
        }
        final View card = detailCard;
        if (!set.anim) {
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) card.getLayoutParams();
            lp.leftMargin = Math.round(to.left);
            lp.topMargin = Math.round(to.top);
            lp.width = Math.max(1, Math.round(to.width()));
            lp.height = Math.max(1, Math.round(to.height()));
            card.setLayoutParams(lp);
            if (dimOverlay != null) dimOverlay.setBackgroundColor(0x80000000);
            if (end != null) end.run();
            return;
        }
        ValueAnimator va = ValueAnimator.ofFloat(0f, 1f);
        va.setDuration(expand ? 420 : 260);
        va.setInterpolator(expand
                ? new PathInterpolator(0.05f, 0.7f, 0.1f, 1f)
                : new PathInterpolator(0.3f, 0f, 0.7f, 0.2f));
        va.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            public void onAnimationUpdate(ValueAnimator a) {
                float t = (Float) a.getAnimatedValue();
                float l = from.left + (to.left - from.left) * t;
                float tp = from.top + (to.top - from.top) * t;
                float r = from.right + (to.right - from.right) * t;
                float b = from.bottom + (to.bottom - from.bottom) * t;
                ViewGroup.LayoutParams raw = card.getLayoutParams();
                if (raw instanceof FrameLayout.LayoutParams) {
                    FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) raw;
                    lp.leftMargin = Math.round(l);
                    lp.topMargin = Math.round(tp);
                    lp.width = Math.max(1, Math.round(r - l));
                    lp.height = Math.max(1, Math.round(b - tp));
                    card.setLayoutParams(lp);
                }
                if (dimOverlay != null) {
                    int alpha = Math.round(0x80 * (expand ? t : 1f - t));
                    dimOverlay.setBackgroundColor(alpha << 24);
                }
                // 只让卡片本体淡入/淡出 —— 内容始终保持完全不透明。
                // 以前给内容做 alpha 淡入，一旦某个分支漏了复位，整块内容就永久不可见
                // （就是「表单一片空白」）。这条路径从此不存在。
                card.setAlpha(expand ? Theme.clamp(t / 0.35f, 0f, 1f) : Theme.clamp(1f - t, 0f, 1f));
                // 来源格子跟着一起淡出/淡入，收回时和卡片同一帧还原，不会闪。
                // 注意：detailSource 可能是 rootFrame（整屏根布局，比如「手动加课」这类
                // 跟某个格子无关的弹窗）—— 那就绝对不能淡它，否则整屏连卡片一起变透明，
                // 看起来就是「全屏变白、点什么都没反应」。
                if (detailSource != null && detailSource != rootFrame) {
                    detailSource.setAlpha(expand
                            ? Theme.clamp(1f - t / 0.3f, 0f, 1f)
                            : Theme.clamp((t - 0.55f) / 0.45f, 0f, 1f));
                }
            }
        });
        va.addListener(new Animator.AnimatorListener() {
            public void onAnimationStart(Animator a) {}
            public void onAnimationCancel(Animator a) {}
            public void onAnimationRepeat(Animator a) {}
            public void onAnimationEnd(Animator a) {
                // 兜底：不管动画怎么走，结束时卡片与内容一定可见
                if (detailCard != null) detailCard.setAlpha(1f);
                if (detailContent != null) detailContent.setAlpha(1f);
                if (end != null) end.run();
            }
        });
        va.start();
    }

    private LinearLayout buildDetailCard(final List<Course> cs) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(round(th.surface, chipRadius(), 0, 0));
        card.setElevation(dp(12));
        card.setClipToOutline(true);
        card.setPadding(dp(16), dp(14), dp(16), dp(12));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        ImageView mascot = new ImageView(this);
        mascot.setImageResource(R.drawable.ic_mascot);
        mascot.setScaleType(ImageView.ScaleType.CENTER_CROP);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(dp(30), dp(30));
        mlp.rightMargin = dp(10);
        head.addView(mascot, mlp);
        TextView title = new TextView(this);
        title.setText(cs.size() == 1
                ? (cs.get(0).name.isEmpty() ? "课程详情" : cs.get(0).name)
                : (cs.get(0).name + " 等 " + cs.size() + " 门"));
        title.setTextColor(th.onSurface);
        title.setTextSize(17);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(title);
        ImageView close = iconButton(R.drawable.ic_close, th.onSurfaceVariant, "关闭", new View.OnClickListener() {
            public void onClick(View v) { closeDetail(); }
        });
        head.addView(close);
        card.addView(head);

        detailContent = new LinearLayout(this);
        detailContent.setOrientation(LinearLayout.VERTICAL);
        card.addView(detailContent);

        for (int i = 0; i < cs.size(); i++) {
            Course c = cs.get(i);
            if (i > 0) {
                View line = new View(this);
                line.setBackgroundColor(Theme.pct(th.outlineVariant, 140));
                LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
                llp.topMargin = dp(10);
                llp.bottomMargin = dp(6);
                detailContent.addView(line, llp);
            }
            if (cs.size() > 1) {
                TextView nm = new TextView(this);
                nm.setText(c.name);
                nm.setTextColor(th.primary);
                nm.setTextSize(15);
                nm.setTypeface(Typeface.DEFAULT_BOLD);
                nm.setPadding(0, dp(8), 0, dp(2));
                detailContent.addView(nm);
            }
            addDetailRow(detailContent, R.drawable.ic_today,
                    c.isOther() ? "无固定时间" : DAY_NAME[c.day] + " " + periodLabel(c)
                            + (set.timeText(c.startPeriod).isEmpty() ? "" : "　" + set.timeText(c.startPeriod)),
                    "时间");
            addDetailRow(detailContent, R.drawable.ic_calendar, c.weeks.isEmpty()
                    ? (c.weeksText.isEmpty() ? "未知" : c.weeksText + "周")
                    : "第 " + c.weeksSummary() + " 周", "周次");
            if (!c.teacher.isEmpty()) addDetailRow(detailContent, R.drawable.ic_sparkle, c.teacher, "教师");
            if (!c.room.isEmpty()) addDetailRow(detailContent, R.drawable.ic_grid, c.room, "地点");
            if (!c.campus.isEmpty()) addDetailRow(detailContent, R.drawable.ic_grid, c.campus, "校区");
            if (!c.examType.isEmpty()) addDetailRow(detailContent, R.drawable.ic_check, c.examType, "考核");
            if (!c.note.isEmpty()) addDetailRow(detailContent, R.drawable.ic_sparkles, c.note, "备注");
        }
        // 自绘的删除按钮（不用 filledButton —— 它的图标是 onPrimary 白色，
        // 配浅色底会看不见）
        TextView delBtn = new TextView(this);
        delBtn.setText(cs.size() > 1 ? "删除这些课" : "删除这门课");
        delBtn.setTextSize(13.5f);
        delBtn.setTypeface(Typeface.DEFAULT_BOLD);
        delBtn.setGravity(Gravity.CENTER);
        delBtn.setPadding(dp(14), dp(11), dp(14), dp(11));
        delBtn.setTextColor(th.primary);
        delBtn.setBackground(ripple(Theme.pct(th.primary, 20),
                Theme.pct(th.surfaceContainerHigh, 100), chipRadius(), 0, 0));
        delBtn.setClickable(true);
        final List<Course> targets = new ArrayList<Course>(cs);
        delBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { askDeleteScope(targets, v); }
        });
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.topMargin = dp(12);
        detailContent.addView(delBtn, dlp);

        TextView hint = new TextView(this);
        hint.setText("长按格子可以看 PDF 原文");
        hint.setTextColor(Theme.pct(th.onSurfaceVariant, 170));
        hint.setTextSize(11);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, dp(8), 0, 0);
        detailContent.addView(hint);
        Fonts.apply(card);
        return card;
    }

    /**
     * 删除前先问清楚范围：这一格（这一节）删掉，还是同名的那门课整体删掉。
     * 同名 = 同一门课在别的星期/别的节次的其它安排。
     */
    private void askDeleteScope(final List<Course> here, final View src) {
        String nm = here.size() == 1 ? here.get(0).name : "";
        // 「这门课」= 整学期里这门课的所有安排（同名）
        final List<Course> wholeCourse = new ArrayList<Course>();
        for (Course c : courses) if (!nm.isEmpty() && c.name.equals(nm)) wholeCourse.add(c);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setText("删除这节课，还是删除这门课？");
        t.setTextColor(th.primary);
        t.setTextSize(16);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        box.addView(t);
        TextView sub = new TextView(this);
        sub.setText(nm.isEmpty()
                ? ("这一格里有 " + here.size() + " 门课")
                : ("「" + nm + "」本学期共 " + wholeCourse.size() + " 节课"));
        sub.setTextColor(th.onSurfaceVariant);
        sub.setTextSize(11.5f);
        sub.setPadding(0, dp(6), 0, dp(12));
        box.addView(sub);

        final List<Course> justThis = new ArrayList<Course>(here);
        box.addView(twoChoice("删除这节课", "只删掉你选中的这个课程格子", false, new View.OnClickListener() {
            public void onClick(View v) { deleteCoursesNow(justThis, "这节课", v); }
        }));

        if (!nm.isEmpty() && wholeCourse.size() > 1) {
            box.addView(twoChoice("删除这门课", "本学期这门课的 " + wholeCourse.size() + " 节全部删掉",
                    true, new View.OnClickListener() {
                        public void onClick(View v) { deleteCoursesNow(wholeCourse, "这门课", v); }
                    }));
        }
        showCardDialog(src, "删除课程", null, box, null, null);
    }

    /** 删除范围里的两个并列按钮 */
    private View twoChoice(String title, String sub, boolean danger, View.OnClickListener l) {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(14), dp(12), dp(14), dp(12));
        int fg = danger ? 0xFFB3261E : th.primary;   // 危险动作给一个明确的红
        b.setBackground(ripple(Theme.pct(fg, 20), Theme.pct(th.surfaceContainerHigh, 100),
                chipRadius(), Math.max(1, dp(0.6f)), Theme.pct(fg, 90)));
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextSize(14.5f);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(fg);
        t.setGravity(Gravity.CENTER);
        b.addView(t);
        TextView s2 = new TextView(this);
        s2.setText(sub);
        s2.setTextSize(11f);
        s2.setTextColor(th.onSurfaceVariant);
        s2.setGravity(Gravity.CENTER);
        s2.setPadding(0, dp(3), 0, 0);
        b.addView(s2);
        b.setClickable(true);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        b.setLayoutParams(lp);
        return b;
    }

    /** 删除范围卡片里的一行选项 */
    private TextView menuBtnPlain(String title, String sub, boolean danger) {
        TextView tv = new TextView(this);
        tv.setText(title + "\n" + sub);
        tv.setTextSize(13.5f);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextColor(th.onSurface);
        tv.setLineSpacing(0, 1.15f);
        tv.setPadding(dp(14), dp(11), dp(14), dp(11));
        tv.setBackground(ripple(Theme.pct(th.primary, 18),
                Theme.pct(th.surfaceContainerHigh, 100), chipRadius(), 0, 0));
        tv.setClickable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        tv.setLayoutParams(lp);
        return tv;
    }

    /** 真正执行删除；scope 只用于提示文案 */
    private void deleteCoursesNow(final List<Course> cs, final String scope, final View src) {
        final List<Course> removed = new ArrayList<Course>();
        final List<Integer> at = new ArrayList<Integer>();
        for (Course c : cs) {
            int idx = courses.indexOf(c);
            if (idx >= 0) {
                at.add(idx);
                removed.add(courses.remove(idx));
            }
        }
        forceCloseDetail();
        String what = "「" + removed.get(0).name + "」";
        showCardDialog(null, "已删除 " + scope,
                (removed.size() == 1 ? what + " 的这一节安排" : ("共 " + removed.size() + " 节安排"))
                        + "已从课表移除。\n手机上原本的 PDF / Excel 不受影响。",
                null, new String[]{"知道了", "撤销"}, new Runnable[]{null, new Runnable() {
                    public void run() {
                        for (int i = removed.size() - 1; i >= 0; i--) {
                            int idx = Math.max(0, Math.min(at.get(i), courses.size()));
                            courses.add(idx, removed.get(i));
                        }
                        saveCourses();
                        computeDayColors();
                        render();
                        toast("已撤销");
                    }
                }});
        saveCourses();
        computeDayColors();
        render();
    }

    /** PDF / 原文件里的原始信息卡片 */
    private LinearLayout buildRawCard(Course c) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(round(th.surface, chipRadius(), 0, 0));
        card.setElevation(dp(12));
        card.setClipToOutline(true);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        ImageView mascot = new ImageView(this);
        mascot.setImageResource(R.drawable.ic_mascot);
        mascot.setScaleType(ImageView.ScaleType.CENTER_CROP);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(dp(30), dp(30));
        mlp.rightMargin = dp(10);
        head.addView(mascot, mlp);
        TextView title = new TextView(this);
        title.setText("原文件信息");
        title.setTextColor(th.onSurface);
        title.setTextSize(17);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(title);
        head.addView(iconButton(R.drawable.ic_close, th.onSurfaceVariant, "关闭",
                new View.OnClickListener() {
                    public void onClick(View v) { closeDetail(); }
                }));
        card.addView(head);

        LinearLayout holder = new LinearLayout(this);
        holder.setOrientation(LinearLayout.VERTICAL);
        detailContent = holder;
        card.addView(holder);

        TextView sub = new TextView(this);
        sub.setText(c.name.isEmpty() ? "未识别课程" : c.name);
        sub.setTextColor(th.primary);
        sub.setTextSize(13.5f);
        sub.setTypeface(Typeface.DEFAULT_BOLD);
        sub.setPadding(0, dp(6), 0, dp(6));
        holder.addView(sub);

        ScrollView sv = new ScrollView(this);
        TextView raw = new TextView(this);
        String text = c.raw == null ? "" : c.raw.replace(" || ", "\n");
        raw.setText(TextUtils.isEmpty(text) ? "（没有原始信息）" : text);
        raw.setTextColor(th.onSurfaceVariant);
        raw.setTextSize(12f);
        raw.setLineSpacing(dp(2), 1.15f);
        sv.addView(raw);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        holder.addView(sv, slp);
        Fonts.apply(card);
        return card;
    }

    private void addDetailRow(LinearLayout parent, int icon, String value, String label) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.TOP);
        row.setPadding(0, dp(5), 0, dp(5));
        ImageView iv = new ImageView(this);
        iv.setImageResource(icon);
        iv.setColorFilter(th.primary);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(18), dp(18));
        ilp.rightMargin = dp(10);
        ilp.topMargin = dp(2);
        row.addView(iv, ilp);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView l = new TextView(this);
        l.setText(label);
        l.setTextColor(th.onSurfaceVariant);
        l.setTextSize(11f);
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextColor(th.onSurface);
        v.setTextSize(14f);
        col.addView(l);
        col.addView(v);
        row.addView(col);
        parent.addView(row);
    }

    /** 应用内统一的自绘卡片弹窗：和课程格子、关于页同一套「从哪来回哪去」非线性展开动画 */
    private void showCardDialog(String title, CharSequence msg, String[] labels, final Runnable[] actions) {
        showCardDialog(null, title, msg, null, labels, actions);
    }

    /**
     * 通用卡片弹窗。
     * content 会放在标题下方（表单类弹窗用）；以前这个参数漏了 ——
     * 于是「手动加课」的整份表单根本没被加进卡片，卡片中间是空的。
     */
    private void showCardDialog(View src, String title, CharSequence msg, View content, String[] labels,
                                final Runnable[] actions) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(round(th.surface, chipRadius(), 0, 0));
        card.setElevation(dp(12));
        card.setClipToOutline(true);
        card.setPadding(dp(16), dp(14), dp(16), dp(12));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        ImageView mascot = new ImageView(this);
        mascot.setImageResource(R.drawable.ic_mascot);
        mascot.setScaleType(ImageView.ScaleType.CENTER_CROP);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(dp(30), dp(30));
        mlp.rightMargin = dp(10);
        head.addView(mascot, mlp);
        TextView t = new TextView(this);
        t.setText(title == null ? "" : title);
        t.setTextColor(th.onSurface);
        t.setTextSize(17);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(t);
        head.addView(iconButton(R.drawable.ic_close, th.onSurfaceVariant, "关闭",
                new View.OnClickListener() {
                    public void onClick(View v) { closeDetail(); }
                }));
        card.addView(head);

        LinearLayout holder = new LinearLayout(this);
        holder.setOrientation(LinearLayout.VERTICAL);
        card.addView(holder);
        // 区分两种情况：
        //  A. 重新展开一张卡片（cardFresh=true）：新的表单要留给 animateCard 做淡入，绝不能藏
        //  B. 在已展开的卡片里换内容（cardFresh=false）：旧内容要藏掉，新内容直接可见
        //     （这时不会再播展开动画）。以前没区分，把新表单也一起设成了透明 → 空白卡片。
        if (detailContent != null) {
            if (!cardFresh) {
                detailContent.setAlpha(0f);
                detailContent = null;
            }
        }
        if (content != null) {
            if (content.getParent() instanceof ViewGroup) {
                ((ViewGroup) content.getParent()).removeView(content);
            }
            holder.addView(content, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            // 内容永远保持可见（不做 alpha 动画）——见 animateCard 里的说明
            content.setAlpha(1f);
            detailContent = null;
        }

        ScrollView sv = new ScrollView(this);
        TextView m = new TextView(this);
        m.setText(msg == null ? "" : msg);
        m.setTextColor(th.onSurfaceVariant);
        m.setTextSize(12.5f);
        m.setLineSpacing(dp(2), 1.15f);
        m.setPadding(0, dp(8), 0, dp(2));
        sv.addView(m);
        if (msg != null && msg.length() > 0) {
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            slp.topMargin = dp(4);
            card.addView(sv, slp);
        }

        if (labels != null && labels.length > 0) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.topMargin = dp(14);
            row.setLayoutParams(rlp);
            for (int i = 0; i < labels.length; i++) {
                final int idx = i;
                boolean primary = (i == labels.length - 1);
                TextView b = new TextView(this);
                b.setText(labels[i]);
                b.setTextSize(13);
                b.setTypeface(Typeface.DEFAULT_BOLD);
                b.setGravity(Gravity.CENTER);
                b.setPadding(dp(16), dp(10), dp(16), dp(10));
                if (primary) {
                    b.setTextColor(th.onPrimary);
                    b.setBackground(filledRound(th.primary, chipRadius()));
                } else {
                    b.setTextColor(th.primary);
                    b.setBackground(filledRound(Theme.pct(th.primary, 24), chipRadius()));
                }
                b.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        closeDetail();
                        if (actions != null && idx < actions.length && actions[idx] != null) {
                            actions[idx].run();
                        }
                    }
                });
                LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                blp.leftMargin = dp(8);
                row.addView(b, blp);
            }
            card.addView(row);
        }

        Fonts.apply(card);
        openCardFrom(rootFrame, card);
    }

    /** 长按空白格子：手工加一门课 */
    private boolean manualOpen;

    private void openManualAdd(final int dayIn, final int periodIn) { openManualAdd(null, dayIn, periodIn); }

    private void openManualAdd(final View src, final int dayIn, final int periodIn) {
        // 双击/长按可能连触发两次；第二次会把已经展开的卡片搅乱，这里直接忽略
        if (manualOpen || detailOpen || detailCard != null) return;
        manualOpen = true;
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            public void run() { manualOpen = false; }
        }, 900);
        final int[] dd = new int[]{dayIn >= 1 ? dayIn : Math.max(1, Math.min(7, Settings.todayDow()))};
        final int day = dd[0];
        final int period = periodIn > 0 ? periodIn : firstFreePeriod(day);
        // 挡「长按抬手那一刻的点击穿透」用独立标志，绝不碰 detailOpen
        // —— detailOpen 同时还是「卡片是否开着」的判据，之前在这里把它设成 false，
        //    结果 closeDetail 认为卡片没开，直接移除、**退出动画整个没了**。
        swallowClicksUntil = System.currentTimeMillis() + 600;
        final EditText name = cardEdit("课程名");
        final EditText weeks = cardEdit("1-16");
        final EditText room = cardEdit("选填，例如 沙河二教 216M");
        final EditText teacher = cardEdit("选填");
        final int[] st = new int[]{period};   // 起始节
        final int[] ep = new int[]{period};   // 结束节

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(this);
        title.setText("添加课程");
        title.setTextColor(th.primary);
        title.setTextSize(16.5f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        box.addView(title);

        final TextView sub = new TextView(this);
        sub.setText(DAY_NAME[dd[0]] + "　第 " + period + " 节"
                + (dayIn >= 1 ? "" : "（默认按今天，可在下面改）"));
        sub.setTextColor(th.onSurfaceVariant);
        sub.setTextSize(11.5f);
        sub.setPadding(0, dp(5), 0, dp(8));
        box.addView(sub);

        box.addView(cardField("星期（默认按今天）", buildDayRow(dd, sub, period)));
        box.addView(cardField("课程名 *", name));
        box.addView(cardField("上课时间", buildSpanRow(day, period, st, ep)));
        box.addView(cardField("周数 *（如 1-16，或 1,3,5）", weeks));
        box.addView(cardField("上课地点", room));
        box.addView(cardField("上课老师", teacher));

        // 关键：卡片展开时初始尺寸只有「格子」那么大（约 100x50px），
        // 如果内容一开始就可见，前几帧会被卡片裁成一条、然后突然正常 —— 看着就是「动画不对」。
        // 所以把表单挂成 detailContent，让 animateCard 的错峰淡入把它在「卡片够大之后」才显出来。
        // 淡入窗口按「尺寸是否到位」算，而不是按时间，小卡片展开时也安全。
        detailContent = box;
        final View formRef = box;
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            public void run() { if (detailOpen) formRef.setAlpha(1f); }
        }, 800);   // 兜底：动画万一没跑，也要让表单可见可点
        // 这里以前写的是 detailOpen = false（为了让卡片能开），后来发现它会毁掉退出动画，已改
        // 只留标题栏的 ✘ 一个关闭入口（用户要求），底部只放「添加」
        showCardDialog(src, "添加课程", null, box, new String[]{"添加"}, new Runnable[]{new Runnable() {
            public void run() {
                String nm = name.getText().toString().trim();
                if (nm.isEmpty()) { toast("先填课程名"); return; }
                int[] ws = CourseParser.parseWeeks(weeks.getText().toString());
                if (ws.length == 0) { toast("周数不对，例如填 1-16"); return; }
                Course c = new Course();
                c.name = nm;
                c.day = dd[0];
                c.startPeriod = st[0];
                c.endPeriod = ep[0];
                for (int w : ws) c.weeks.add(w);
                c.weeksText = Course.compress(courseWeeks(c));
                c.room = room.getText().toString().trim();
                c.teacher = teacher.getText().toString().trim();
                c.raw = "手动添加";
                courses.add(c);
                if (semester == null || semester.isEmpty()) semester = "手动添加";
                if (sourceName == null || sourceName.isEmpty()) sourceName = "手动添加";
                maxWeek = Math.max(maxWeek, ws[ws.length - 1]);
                if (headerSub != null && !semester.isEmpty()) headerSub.setText(semester);
                saveCourses();
                computeDayColors();
                render();
                toast("已添加 " + nm + "（" + c.dayName() + " " + c.periodText() + "）");
            }
        }});
    }

    /** 这一天第一个还没排课的节次（顶栏入口用的默认值） */
    private int firstFreePeriod(int day) {
        for (int p = 1; p <= 13; p++) {
            boolean used = false;
            for (Course c : courses) {
                if (c.isOther() || c.day != day || c.startPeriod <= 0) continue;
                int e = Math.max(c.endPeriod, c.startPeriod);
                if (p >= Math.max(1, c.startPeriod) && p <= e) { used = true; break; }
            }
            if (!used) return p;
        }
        return 1;
    }

    /** 课程数据落盘（导入和手工加课都走这里） */
    private void saveCourses() {
        try {
            Store.Data d = new Store.Data();
            d.semester = semester;
            d.source = sourceName;
            d.maxWeek = maxWeek;
            d.importedAt = System.currentTimeMillis();
            d.courses.addAll(courses);
            Store.save(this, d);
        } catch (Throwable ignore) {}
    }

    private static List<Integer> courseWeeks(Course c) {
        return new ArrayList<Integer>(c.weeks);
    }

    /** 周一~周日 七个胶囊 */
    private View buildDayRow(final int[] dd, final TextView sub, final int period) {
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        hs.addView(row);
        final List<TextView> cs = new ArrayList<TextView>();
        final String[] names = {"", "一", "二", "三", "四", "五", "六", "日"};
        for (int d = 1; d <= 7; d++) {
            final int day = d;
            final TextView chip = new TextView(this);
            chip.setText("周" + names[d]);
            chip.setTextSize(12.5f);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(11), dp(6), dp(11), dp(6));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(5);
            chip.setLayoutParams(lp);
            chip.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    dd[0] = day;
                    sub.setText(DAY_NAME[day] + "　第 " + period + " 节");
                    for (int k = 0; k < cs.size(); k++) paintSpanChip(cs.get(k), k + 1, dd[0], -1, true);
                }
            });
            cs.add(chip);
            row.addView(chip);
        }
        for (int k = 0; k < cs.size(); k++) paintSpanChip(cs.get(k), k + 1, dd[0], -1, true);
        return hs;
    }

    /** 手工加课时用作字段标题的小标签 */
    private View cardField(String label, View field) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView l = new TextView(this);
        l.setText(label);
        l.setTextColor(th.onSurfaceVariant);
        l.setTextSize(11f);
        l.setPadding(dp(2), dp(8), 0, dp(3));
        col.addView(l);
        col.addView(field);
        return col;
    }

    /** 卡片里的输入框（沿用设置页那套圆角样式） */
    private EditText cardEdit(String hint) {
        EditText et = new EditText(this);
        et.setHint(hint);
        et.setSingleLine();
        et.setTextColor(th.onSurface);
        et.setHintTextColor(Theme.pct(th.onSurfaceVariant, 150));
        et.setTextSize(14.5f);
        et.setPadding(dp(12), dp(9), dp(12), dp(9));
        et.setBackground(round(Theme.pct(th.surfaceContainerHigh, 100), chipRadius(), 0, 0));
        return et;
    }

    /** 「第 x ~ y 节」两排胶囊，点一下即可改（沿用课表同一套选中样式） */
    private View buildSpanRow(final int day, final int period, final int[] st, final int[] ep) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        final TextView preview = new TextView(this);
        preview.setText(periodLabel4(st[0], ep[0]));
        preview.setTextColor(th.onSurface);
        preview.setTextSize(15);
        preview.setTypeface(Typeface.DEFAULT_BOLD);
        preview.setPadding(dp(2), 0, 0, dp(2));
        box.addView(preview);
        box.addView(spanLine("起始", period, st, ep, preview));
        box.addView(spanLine("结束", period, st, ep, preview));
        return box;
    }

    /** 一排可选节次；点「起始」时结束会自动跟上，点「结束」时不会早于起始 */
    private View spanLine(final String label, final int period, final int[] st, final int[] ep,
                          final TextView preview) {
        final boolean isStart = "起始".equals(label);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView l = new TextView(this);
        l.setText(label);
        l.setTextColor(th.onSurfaceVariant);
        l.setTextSize(11f);
        l.setLayoutParams(new LinearLayout.LayoutParams(dp(30), ViewGroup.LayoutParams.WRAP_CONTENT));
        row.addView(l);

        final int from = Math.max(1, period - 2);
        final int to = Math.min(15, from + 9);
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        hs.addView(chips);
        row.addView(hs);

        final List<TextView> cs = new ArrayList<TextView>();
        for (int p = from; p <= to; p++) {
            final int pp = p;
            final TextView chip = new TextView(this);
            chip.setText(String.valueOf(p));
            chip.setTextSize(12.5f);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(11), dp(6), dp(11), dp(6));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(5);
            chip.setLayoutParams(lp);
            chip.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    if (isStart) {
                        st[0] = pp;
                        if (ep[0] < pp) ep[0] = pp;      // 结束不能早于起始
                    } else {
                        ep[0] = Math.max(pp, st[0]);
                    }
                    preview.setText(periodLabel4(st[0], ep[0]));
                    for (int k = 0; k < cs.size(); k++) paintSpanChip(cs.get(k), from + k, st[0], ep[0], isStart);
                }
            });
            cs.add(chip);
            chips.addView(chip);
        }
        for (int k = 0; k < cs.size(); k++) paintSpanChip(cs.get(k), from + k, st[0], ep[0], isStart);
        return row;
    }

    private void paintSpanChip(TextView tv, int val, int st, int ep, boolean isStart) {
        boolean on = isStart ? (val == st) : (val == ep);
        tv.setTypeface(on ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        tv.setTextColor(on ? th.onSecondaryContainer : th.onSurfaceVariant);
        tv.setBackground(round(on ? Theme.pct(th.secondaryContainer, 100)
                : Theme.pct(th.surfaceContainerHigh, 100), chipRadius(), 0, 0));
    }

    private static String periodLabel4(int a, int b) {
        return a == b ? ("第 " + a + " 节") : ("第 " + a + " ~ " + b + " 节");
    }

    private android.graphics.drawable.GradientDrawable filledRound(int color, int radiusDp) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    @Override
    public void onBackPressed() {
        if (detailOpen) {
            closeDetail();
            return;
        }
        super.onBackPressed();
    }

    // ---------------------------------------------------------------- 控件工厂

    private ImageView iconButton(int res, int tint, final String hint, View.OnClickListener l) {
        ImageView iv = new ImageView(this);
        iv.setImageResource(res);
        iv.setColorFilter(tint);
        iv.setPadding(dp(12), dp(12), dp(12), dp(12));
        iv.setBackground(rippleRounded(Theme.pct(tint, 14), 24));
        iv.setClickable(true);
        iv.setOnClickListener(l);
        iv.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) {
                toast(hint);
                return true;
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(46), dp(46));
        iv.setLayoutParams(lp);
        return iv;
    }

    private View filledButton(int icon, String text, View.OnClickListener l) {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.HORIZONTAL);
        b.setGravity(Gravity.CENTER_VERTICAL);
        b.setPadding(dp(18), dp(11), dp(20), dp(11));
        b.setBackground(ripple(Theme.pct(th.onPrimary, 18), th.primary, chipRadius(), 0, 0));
        ImageView iv = new ImageView(this);
        iv.setImageResource(icon);
        iv.setColorFilter(th.onPrimary);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(18), dp(18));
        ilp.rightMargin = dp(8);
        b.addView(iv, ilp);
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(th.onPrimary);
        tv.setTextSize(14);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        b.addView(tv);
        b.setClickable(true);
        b.setOnClickListener(l);
        return b;
    }

    private Drawable ripple(int rippleColor, int bgColor, int radiusDp, int strokeDp, int strokeColor) {
        GradientDrawable content = new GradientDrawable();
        content.setColor(bgColor);
        content.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0) content.setStroke(strokeDp, strokeColor);
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(dp(radiusDp));
        return new RippleDrawable(ColorStateList.valueOf(rippleColor), content, mask);
    }

    private Drawable rippleOval(int rippleColor) {
        GradientDrawable mask = new GradientDrawable();
        mask.setShape(GradientDrawable.OVAL);
        mask.setColor(Color.WHITE);
        return new RippleDrawable(ColorStateList.valueOf(rippleColor), null, mask);
    }

    private GradientDrawable round(int color, int radiusDp, int strokeDp, int strokeColor) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0) g.setStroke(strokeDp, strokeColor);
        return g;
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    // ---------------------------------------------------------------- 导入

    private void pickPdf() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/pdf",
                "application/vnd.ms-excel",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "application/octet-stream",
                "text/html",
                "text/csv"
        });
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivityForResult(i, REQ_PICK);
        } catch (Exception e) {
            Intent j = new Intent(Intent.ACTION_GET_CONTENT);
            j.setType("*/*");
            try { startActivityForResult(Intent.createChooser(j, "选择课表 PDF"), REQ_PICK); }
            catch (Exception e2) { toast("没有找到可用的文件选择器"); }
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_PICK && res == RESULT_OK && data != null && data.getData() != null) {
            importFrom(data.getData());
        }
    }

    private void importFrom(final Uri uri) {
        if (parsing) return;
        String name = uri.getLastPathSegment();
        if (name == null) name = "课表.pdf";
        final String display = name;
        setBusy(true, "正在读取 " + display + " …");
        new Thread(new Runnable() {
            public void run() {
                File tmp = new File(getCacheDir(), "import.pdf");
                String err = null;
                CourseParser.Result r = null;
                try {
                    InputStream in = getContentResolver().openInputStream(uri);
                    FileOutputStream out = new FileOutputStream(tmp);
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    out.close();
                    in.close();
                    post("正在解析课表 …");
                    byte[] head = new byte[8];
                    java.io.FileInputStream fis = new java.io.FileInputStream(tmp);
                    int hn = fis.read(head);
                    fis.close();
                    boolean isPdf = hn >= 4 && head[0] == '%' && head[1] == 'P' && head[2] == 'D' && head[3] == 'F';
                    boolean isZip = hn >= 2 && head[0] == 'P' && head[1] == 'K';
                    boolean isOle = hn >= 4 && (head[0] & 0xFF) == 0xD0 && (head[1] & 0xFF) == 0xCF;
                    if (isPdf) {
                        r = CourseParser.parse(tmp);
                    } else {
                        Xls.Grid grid = Xls.read(tmp);
                        r = ExcelParser.parse(grid);
                        Log.i(TAG, "XLS grid " + grid.rowCount() + "x" + grid.colCount()
                                + (isZip ? " (xlsx)" : isOle ? " (xls)" : " (html)"));
                    }
                } catch (Throwable t) {
                    err = t.getClass().getSimpleName() + ": " + t.getMessage();
                    Log.e(TAG, "IMPORT_FAIL", t);
                }
                final CourseParser.Result fr = r;
                final String ferr = err;
                runOnUiThread(new Runnable() {
                    public void run() {
                        setBusy(false, null);
                        if (ferr != null) {
                            String hint = "支持 PDF，以及 Excel（.xls / .xlsx）导出的课表。";
                            if (ferr.contains("EACCES") || ferr.contains("Permission denied")
                                    || ferr.contains("FileNotFound")) {
                                hint = "系统不允许直接读取这个路径。\n"
                                        + "请改用应用里的导入按钮，通过文件选择器打开。";
                            }
                            showCardDialog("读取失败", ferr + "\n\n" + hint,
                                    new String[]{"好", "去选择文件"},
                                    new Runnable[]{null, new Runnable() {
                                        public void run() { pickPdf(); }
                                    }});
                            return;                        }
                        courses.clear();
                        courses.addAll(fr.courses);
                        semester = fr.semester;
                        maxWeek = Math.max(1, fr.maxWeek);
                        sourceName = display;
                        showAll = false;
                        currentWeek = clampWeek(set.weekOfToday());
                        if (headerSub != null && !semester.isEmpty()) headerSub.setText(semester);
                        Store.Data d = new Store.Data();
                        d.semester = semester;
                        d.source = sourceName;
                        d.maxWeek = maxWeek;
                        d.importedAt = System.currentTimeMillis();
                        d.courses.addAll(courses);
                        Store.save(MainActivity.this, d);
                        try { Reminder.reschedule(MainActivity.this); } catch (Throwable ignore) {}
                        computeDayColors();
                        buildUi();
                        render();
                        Log.i(TAG, "IMPORT_OK source=" + sourceName + " maxWeek=" + maxWeek
                                + " courses=" + courses.size());
                        int n = 0;
                        for (Course c : courses) if (!c.isOther()) n++;
                        if (n == 0) {
                            showCardDialog("没识别出课程",
                                    "没能从这份 PDF 里解析出课程表。\n"
                                            + "目前对教务系统导出的「表格型」课表支持最好，"
                                            + "扫描件 / 图片型 PDF 无法识别。",
                                    new String[]{"好"}, null);
                        } else {
                            toast("导入成功：共 " + n + " 门课，最长 " + maxWeek + " 教学周");
                        }
                    }
                });
            }
        }).start();
    }

    private void setBusy(boolean busy, String msg) {
        parsing = busy;
        if (btnImport != null) {
            btnImport.setEnabled(!busy);
            btnImport.setAlpha(busy ? 0.5f : 1f);
        }
        if (busy && msg != null) infoText.setText(msg);
    }

    private void post(final String msg) {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            public void run() { if (parsing) infoText.setText(msg); }
        });
    }
}
