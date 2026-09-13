package com.dsh.coursetable;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.animation.Animator;
import android.animation.LayoutTransition;
import android.animation.ValueAnimator;
import android.graphics.RectF;
import android.view.animation.OvershootInterpolator;
import android.view.animation.PathInterpolator;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.DragEvent;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.HorizontalScrollView;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Calendar;

/** MD3 设置页：作息时间、主题色、背景图（透明度/模糊）、圆角、动画、开学日期、数据。 */
public class SettingsActivity extends Activity {

    private static final int REQ_IMG = 3001;

    private static final int[] PRESETS = {
            0xFF66CCFF, 0xFF4FB3E8, 0xFF5BC8AF, 0xFF7E9CF5,
            0xFFA98BF5, 0xFFF49AC1, 0xFFF5A65B, 0xFF8ED081
    };

    private Settings set;
    private Theme th;
    private LinearLayout rootBox;
    private LinearLayout body;
    private LinearLayout bar;
    private View cornerPreview;
    private FrameLayout overlay;      // 半透明窗口：遮罩 + 面板
    private View scrim;
    private GradientDrawable panelBg;
    private boolean closing;
    private boolean animIn = true;
    private boolean shellBuilt;   // 面板是否已经建过（换主题重建时不再重播进入动画）
    private ScrollView panelScroll;
    private LinearLayout periodBox;
    private View cardDim;      // 卡片遮罩
    private LinearLayout card;  // 展开的卡片（关于 / 开源许可）
    private View cardSource;   // 从哪个控件长出来
    private RectF cardSourceRect; // 打开时记下的来源矩形（控件被重建也能原路返回）
    private TextView startDateValue;   // 学期卡片里的「YYYY-MM-DD 周X」
    private View draggingRow;          // 正在被长按拖动的行

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Fonts.init(getApplicationContext());
        set = Settings.load(this);
        th = Theme.of(set.theme);
        lastLandscape = Bg.isLandscape(this);   // 先记下，避免 onResume 又把面板重建一遍（会吃掉进入动画）
        buildShell();
    }

    @Override
    public void finish() {
        if (closing) {
            super.finish();
            return;
        }
        collapseAndFinish();
    }

    @Override
    public void onBackPressed() {
        if (card != null) {
            closeCard();
            return;
        }
        collapseAndFinish();
    }

    @Override
    protected void onResume() {
        super.onResume();
        set = Settings.load(this);
        th = Theme.of(set.theme);
        if (Bg.isLandscape(this) != lastLandscape) {
            lastLandscape = Bg.isLandscape(this);
            buildShell();
        } else {
            rebuild();
        }
    }

    private boolean lastLandscape;

    // ---------------------------------------------------------------- 骨架

    private void buildShell() {
        overlay = new FrameLayout(this);
        overlay.setBackgroundColor(Color.TRANSPARENT);

        scrim = new View(this);
        scrim.setBackgroundColor(0x00000000);
        scrim.setClickable(true);
        scrim.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { collapseAndFinish(); }
        });
        overlay.addView(scrim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        rootBox = new LinearLayout(this);
        rootBox.setOrientation(LinearLayout.VERTICAL);
        panelBg = round(th.surface, cr(), 0, 0);
        rootBox.setBackground(panelBg);
        rootBox.setClipToOutline(true);
        rootBox.setElevation(dp(10));
        rootBox.setFitsSystemWindows(true);

        bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(Color.TRANSPARENT);
        bar.setPadding(dp(6), dp(8), dp(12), dp(8));

        ImageView back = iconButton(R.drawable.ic_back, th.onSurface, "返回", new View.OnClickListener() {
            public void onClick(View v) { collapseAndFinish(); }
        });
        bar.addView(back);
        TextView title = new TextView(this);
        title.setText("设置");
        title.setTextColor(th.onSurface);
        title.setTextSize(19);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        bar.addView(title);
        rootBox.addView(bar);

        ScrollView sv = new ScrollView(this);
        panelScroll = sv;
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(14), dp(4), dp(14), dp(30));
        sv.addView(body);
        rootBox.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        overlay.addView(rootBox, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(overlay);
        Fonts.apply(overlay);
        rebuild();

        Intent it0 = getIntent();
        boolean willAnimate = !shellBuilt
                && it0 != null && it0.getBooleanExtra("anim", true) && it0.getIntExtra("bx", -1) >= 0;
        shellBuilt = true;
        if (willAnimate) {
            rootBox.setAlpha(0f);
            body.setAlpha(0f);
            bar.setAlpha(0f);
            startEnterAnimation();
        } else {
            scrim.setBackgroundColor(0x80000000);
            rootBox.setAlpha(1f);
        }
    }

    // ------------------------------------------------ 从设置按钮“长出来”的非线性动画

    private void startEnterAnimation() {
        Intent it = getIntent();
        animIn = it == null || it.getBooleanExtra("anim", true);
        final int bx = it == null ? -1 : it.getIntExtra("bx", -1);
        final int by = it == null ? 0 : it.getIntExtra("by", 0);
        final int bw = it == null ? 0 : it.getIntExtra("bw", 0);
        final int bh = it == null ? 0 : it.getIntExtra("bh", 0);
        if (!animIn || bx < 0 || bw <= 0) {
            scrim.setBackgroundColor(0x80000000);
            return;
        }
        rootBox.post(new Runnable() {
            public void run() {
                int[] rl = new int[2];
                overlay.getLocationOnScreen(rl);
                layoutPanelFinal();
                int w = Math.max(1, rootBox.getWidth());
                int h = Math.max(1, rootBox.getHeight());
                // 从按钮大小缩放到全屏；缩放中心 = 按钮中心
                float s0 = Theme.clamp(Math.max((float) bw / w, (float) bh / h), 0.04f, 0.9f);
                int px = Math.round(bx - rl[0] + bw / 2f);
                int py = Math.round(by - rl[1] + bh / 2f);
                rootBox.setScaleX(s0);
                rootBox.setScaleY(s0);
                rootBox.setAlpha(0.35f);
                rootBox.setVisibility(View.VISIBLE);
                body.setAlpha(0f);
                bar.setAlpha(0f);
                animatePanel(s0, px, py, true, null);
            }
        });
    }

    private void collapseAndFinish() {
        if (closing) return;
        closing = true;
        Intent it = getIntent();
        final int bx = it == null ? -1 : it.getIntExtra("bx", -1);
        final int by = it == null ? 0 : it.getIntExtra("by", 0);
        final int bw = it == null ? 0 : it.getIntExtra("bw", 0);
        final int bh = it == null ? 0 : it.getIntExtra("bh", 0);
        if (!animIn || bx < 0 || bw <= 0 || rootBox == null) {
            reallyFinish();
            return;
        }
        int[] rl = new int[2];
        overlay.getLocationOnScreen(rl);
        int w = Math.max(1, rootBox.getWidth());
        int h = Math.max(1, rootBox.getHeight());
        float s0 = Theme.clamp(Math.max((float) bw / w, (float) bh / h), 0.04f, 0.9f);
        int px = Math.round(bx - rl[0] + bw / 2f);
        int py = Math.round(by - rl[1] + bh / 2f);
        animatePanel(s0, px, py, false, new Runnable() {
            public void run() { reallyFinish(); }
        });
    }

    private void reallyFinish() {
        closing = true;
        super.finish();
    }

    /** 和课程格子同一套非线性曲线；纯变换动画（缩放+淡入），不触发重新布局，所以很跟手 */
    private void animatePanel(final float s0, final int pivotX, final int pivotY,
                              final boolean expand, final Runnable end) {
        if (rootBox == null) return;
        rootBox.setPivotX(pivotX);
        rootBox.setPivotY(pivotY);
        ValueAnimator va = ValueAnimator.ofFloat(0f, 1f);
        va.setDuration(expand ? 400 : 240);
        va.setInterpolator(expand
                ? new PathInterpolator(0.05f, 0.7f, 0.1f, 1f)
                : new PathInterpolator(0.3f, 0f, 0.7f, 0.2f));
        va.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            public void onAnimationUpdate(ValueAnimator a) {
                float t = (Float) a.getAnimatedValue();
                float k = expand ? (s0 + (1f - s0) * t) : (1f - (1f - s0) * t);
                rootBox.setScaleX(k);
                rootBox.setScaleY(k);
                rootBox.setAlpha(expand ? Theme.clamp(0.35f + 0.65f * t, 0f, 1f)
                                        : Theme.clamp(1f - t * 0.9f, 0f, 1f));
                scrim.setBackgroundColor((Math.round(0x80 * (expand ? t : 1f - t))) << 24);
                if (expand) {
                    body.setAlpha(Theme.clamp((t - 0.3f) / 0.7f, 0f, 1f));
                    bar.setAlpha(Theme.clamp((t - 0.15f) / 0.85f, 0f, 1f));
                }
            }
        });
        va.addListener(new Animator.AnimatorListener() {
            public void onAnimationStart(Animator a) {}
            public void onAnimationCancel(Animator a) {}
            public void onAnimationRepeat(Animator a) {}
            public void onAnimationEnd(Animator a) {
                if (expand) {
                    rootBox.setScaleX(1f);
                    rootBox.setScaleY(1f);
                    rootBox.setAlpha(1f);
                    body.setAlpha(1f);
                    bar.setAlpha(1f);
                } else {
                    body.setAlpha(0f);
                    bar.setAlpha(0f);
                }
                if (end != null) end.run();
            }
        });
        va.start();
    }

    /** 面板最终位置：铺满窗口，留一点边 */
    private void layoutPanelFinal() {
        if (rootBox == null || overlay == null) return;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) rootBox.getLayoutParams();
        lp.leftMargin = 0;
        lp.topMargin = 0;
        lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
        lp.height = ViewGroup.LayoutParams.MATCH_PARENT;
        rootBox.setLayoutParams(lp);
    }

    private void rebuild() {
        final int keepScroll = panelScroll == null ? 0 : panelScroll.getScrollY();
        body.removeAllViews();
        // 每次重建后统一套字体
        body.post(new Runnable() { public void run() { Fonts.apply(rootBox); } });

        // ================= 外观
        body.addView(sectionLabel("外观"));

        LinearLayout themeCard = card();
        themeCard.addView(itemTitle(R.drawable.ic_palette, "主题色", "默认 #66CCFF，也可以从背景图里自动取色"));
        LinearLayout sw = new LinearLayout(this);
        sw.setOrientation(LinearLayout.HORIZONTAL);
        sw.setPadding(0, dp(10), 0, dp(10));
        for (final int c : PRESETS) sw.addView(swatch(c));
        themeCard.addView(sw);
        LinearLayout tr = new LinearLayout(this);
        tr.setOrientation(LinearLayout.HORIZONTAL);
        final View hexBtn = tonalButton(R.drawable.ic_sparkle, "自定义颜色", null);
        hexBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { openCard(hexBtn, buildColorContent()); }
        });
        tr.addView(hexBtn);
        tr.addView(tonalButton(R.drawable.ic_image, "从背景图取色", new View.OnClickListener() {
            public void onClick(View v) { pickFromBg(); }
        }));
        themeCard.addView(tr);
        themeCard.addView(support("当前 " + hex(set.theme) + (Bg.exists(this) ? "" : "　（先选背景图才能取色）")));
        body.addView(themeCard);

        LinearLayout bgCard = card();
        bgCard.addView(itemTitle(R.drawable.ic_image, "课表背景图", "选的图会铺在课表下面，可调透明度和模糊"));
        LinearLayout br = new LinearLayout(this);
        br.setOrientation(LinearLayout.HORIZONTAL);
        br.setGravity(Gravity.CENTER_VERTICAL);
        br.setPadding(0, dp(8), 0, dp(4));
        br.addView(tonalButton(R.drawable.ic_import, Bg.exists(this) ? "更换图片" : "选择图片",
                new View.OnClickListener() {
                    public void onClick(View v) { pickImage(); }
                }));
        if (Bg.hasSrc(this)) {
            Bitmap thb = Bg.thumb(this);
            if (thb != null) {
                ImageView iv = new ImageView(this);
                iv.setImageBitmap(thb);
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(54), dp(54));
                lp.leftMargin = dp(10);
                iv.setLayoutParams(lp);
                iv.setBackground(round(th.surfaceContainer, Math.min(cr(), 14), 0, 0));
                iv.setClipToOutline(true);
                br.addView(iv);
            }
            br.addView(tonalButton(R.drawable.ic_close, "清除", new View.OnClickListener() {
                public void onClick(View v) {
                    Bg.clearAll(SettingsActivity.this);
                    set.bg = "";
                    set.save(SettingsActivity.this);
                    toast("已清除背景图");
                    rebuild();
                }
            }));
        }
        bgCard.addView(br);

        if (Bg.hasSrc(this)) {
            LinearLayout cr = new LinearLayout(this);
            cr.setOrientation(LinearLayout.HORIZONTAL);
            cr.setGravity(Gravity.CENTER_VERTICAL);
            cr.setPadding(0, dp(4), 0, dp(2));
            boolean land = Bg.isLandscape(this);
            cr.addView(cropButton(true, "裁横屏版", land));
            cr.addView(cropButton(false, "裁竖屏版", !land));
            bgCard.addView(cr);
            bgCard.addView(support("横竖屏各用一套裁切（按屏幕比例取景）。当前是"
                    + (land ? "横屏" : "竖屏") + "，用的就是"
                    + (Bg.hasCrop(this, land) ? "对应那套" : "原图居中裁的兜底") + "。"));
        }
        bgCard.addView(sliderRow(R.drawable.ic_opacity, "背景不透明度", set.bgAlpha, 0, 100, "%",
                new OnValue() {
                    public void on(int v) {
                        set.bgAlpha = v;
                        set.save(SettingsActivity.this);
                    }
                }));
        bgCard.addView(sliderRow(R.drawable.ic_blur, "背景模糊", set.bgBlur, 0, 60, "%",
                new OnValue() {
                    public void on(int v) {
                        set.bgBlur = v;
                        set.save(SettingsActivity.this);
                    }
                }));
        body.addView(bgCard);

        LinearLayout lookCard = card();
        lookCard.addView(itemTitle(R.drawable.ic_radius, "课表外观", "格子、卡片的圆角与透明度"));
        lookCard.addView(sliderRow(R.drawable.ic_opacity, "格子不透明度", set.cardAlpha, 0, 100, "%",
                new OnValue() {
                    public void on(int v) {
                        set.cardAlpha = v;
                        set.save(SettingsActivity.this);
                    }
                }));
        lookCard.addView(sliderRow(R.drawable.ic_radius, "圆角", set.corner, 0, 28, "dp", new OnValue() {
            public void on(int v) {
                set.corner = v;
                set.save(SettingsActivity.this);
                if (cornerPreview != null) {
                    cornerPreview.setBackground(round(Theme.pct(th.primary, 55), v, 1, th.outlineVariant));
                }
            }
        }));

        LinearLayout animRow = new LinearLayout(this);
        animRow.setOrientation(LinearLayout.HORIZONTAL);
        animRow.setGravity(Gravity.CENTER_VERTICAL);
        animRow.setPadding(0, dp(6), 0, dp(6));
        ImageView ai = new ImageView(this);
        ai.setImageResource(R.drawable.ic_sparkle);
        ai.setColorFilter(th.primary);
        LinearLayout.LayoutParams ailp = new LinearLayout.LayoutParams(dp(20), dp(20));
        ailp.rightMargin = dp(10);
        animRow.addView(ai, ailp);
        TextView al = new TextView(this);
        al.setText("界面动画");
        al.setTextColor(th.onSurface);
        al.setTextSize(14);
        al.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        animRow.addView(al);
        final View animDot = dotToggle(set.anim);
        animRow.addView(animDot);
        animRow.setClickable(true);
        animRow.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                set.anim = !set.anim;
                set.save(SettingsActivity.this);
                styleDot(animDot, set.anim, true);
            }
        });
        lookCard.addView(animRow);

        LinearLayout palBox = new LinearLayout(this);
        palBox.setOrientation(LinearLayout.VERTICAL);
        palBox.setPadding(0, dp(8), 0, dp(2));

        LinearLayout palHead = new LinearLayout(this);
        palHead.setOrientation(LinearLayout.HORIZONTAL);
        palHead.setGravity(Gravity.CENTER_VERTICAL);
        ImageView pi = new ImageView(this);
        pi.setImageResource(R.drawable.ic_sparkles);
        pi.setColorFilter(th.primary);
        LinearLayout.LayoutParams pilp = new LinearLayout.LayoutParams(dp(20), dp(20));
        pilp.rightMargin = dp(10);
        palHead.addView(pi, pilp);
        LinearLayout pcol = new LinearLayout(this);
        pcol.setOrientation(LinearLayout.VERTICAL);
        pcol.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView pt = new TextView(this);
        pt.setText("课程格子配色");
        pt.setTextColor(th.onSurface);
        pt.setTextSize(14);
        pcol.addView(pt);
        TextView ps = new TextView(this);
        ps.setText("单色＝全部用主题色；取色＝从背景图提取糖果色按课程分配；多彩＝主题色系按天变化");
        ps.setTextColor(th.onSurfaceVariant);
        ps.setTextSize(11f);
        ps.setLineSpacing(0, 1.1f);
        pcol.addView(ps);
        palHead.addView(pcol);
        palBox.addView(palHead);

        LinearLayout modeRow = new LinearLayout(this);
        modeRow.setOrientation(LinearLayout.HORIZONTAL);
        modeRow.setPadding(0, dp(8), 0, 0);
        final String[] modeNames = {"主题色单色", "背景图取色", "主题色多彩"};
        for (int m = 0; m < modeNames.length; m++) {
            final int mode = m;
            modeRow.addView(pickChip(modeNames[m], set.colorMode == mode, new View.OnClickListener() {
                public void onClick(View v) {
                    set.colorMode = mode;
                    set.save(SettingsActivity.this);
                    rebuild();
                }
            }));
        }
        palBox.addView(modeRow);
        lookCard.addView(palBox);

        cornerPreview = new View(this);
        cornerPreview.setBackground(round(Theme.pct(th.primary, 55), cr(), 1, th.outlineVariant));
        LinearLayout.LayoutParams cplp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(54));
        cplp.topMargin = dp(8);
        cornerPreview.setLayoutParams(cplp);
        lookCard.addView(cornerPreview);
        body.addView(lookCard);

        // ================= 学期
        body.addView(sectionLabel("学期"));
        LinearLayout dateCard = card();
        LinearLayout dr = new LinearLayout(this);
        dr.setOrientation(LinearLayout.HORIZONTAL);
        dr.setGravity(Gravity.CENTER_VERTICAL);
        ImageView di = new ImageView(this);
        di.setImageResource(R.drawable.ic_today);
        di.setColorFilter(th.primary);
        LinearLayout.LayoutParams dilp = new LinearLayout.LayoutParams(dp(24), dp(24));
        dilp.rightMargin = dp(12);
        dr.addView(di, dilp);
        LinearLayout dcol = new LinearLayout(this);
        dcol.setOrientation(LinearLayout.VERTICAL);
        dcol.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView dt = new TextView(this);
        dt.setText("开学日期（第 1 周周一）");
        dt.setTextColor(th.onSurface);
        dt.setTextSize(14);
        dcol.addView(dt);
        startDateValue = new TextView(this);
        startDateValue.setText(set.startDate + "  " + weekdayCn(set.startDate));
        startDateValue.setTextColor(th.onSurfaceVariant);
        startDateValue.setTextSize(12);
        dcol.addView(startDateValue);
        dr.addView(dcol);
        // 自绘卡片（年月日胶囊 + 大预览），不再用系统 DatePickerDialog
        final View dateBtn = tonalButton(R.drawable.ic_calendar, "修改", null);
        dateBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { openCard(dateBtn, buildDateContent()); }
        });
        dr.addView(dateBtn);
        dateCard.addView(dr);
        dateCard.addView(support("今天是第 " + set.weekOfToday() + " 周" + Settings.weekdayCn(Settings.todayDow())
                + "，课表里会用主题色标出今天那一列。"));
        body.addView(dateCard);

        // ================= 上课时间
        body.addView(sectionLabel("上课时间"));
        body.addView(support2("课程和休息是同级的两行，都能单独改开始/结束时间，休息还能单独改名。"
                + "每一行右侧的 ➕ 在它后面加一节或一段休息，✘ 只删掉那一行自己。"
                + "整张表按时间自动排序：改了时间，行的先后和节次号会自己跟着变。"));
        periodBox = new LinearLayout(this);
        periodBox.setOrientation(LinearLayout.VERTICAL);
        periodBox.setLayoutTransition(null);   // 关掉容器默认的增删动画，拖动换位时不闪
        periodBox.setOnDragListener(new View.OnDragListener() {
            public boolean onDrag(View v, DragEvent e) {
                if (draggingRow == null) return false;
                switch (e.getAction()) {
                    case DragEvent.ACTION_DRAG_STARTED:
                    case DragEvent.ACTION_DRAG_LOCATION:
                        onDragMove(e.getY());            // 实时换位
                        dragAutoScroll(e.getY());
                        return true;
                    case DragEvent.ACTION_DROP:
                    case DragEvent.ACTION_DRAG_ENDED:
                        finishDrag();
                        return true;
                }
                return false;
            }
        });
        body.addView(periodBox);
        fillPeriods();

        body.addView(sectionLabel("上课提醒"));        // ================= 上课提醒
        LinearLayout remCard = card();
        LinearLayout remRow = new LinearLayout(this);
        remRow.setOrientation(LinearLayout.HORIZONTAL);
        remRow.setGravity(Gravity.CENTER_VERTICAL);
        ImageView ri = new ImageView(this);
        ri.setImageResource(R.drawable.ic_clock);
        ri.setColorFilter(th.primary);
        LinearLayout.LayoutParams rilp = new LinearLayout.LayoutParams(dp(22), dp(22));
        rilp.rightMargin = dp(12);
        remRow.addView(ri, rilp);
        LinearLayout rcol = new LinearLayout(this);
        rcol.setOrientation(LinearLayout.VERTICAL);
        rcol.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView rt = new TextView(this);
        rt.setText("上课前提醒");
        rt.setTextColor(th.onSurface);
        rt.setTextSize(14.5f);
        rt.setTypeface(Typeface.DEFAULT_BOLD);
        rcol.addView(rt);
        TextView rs = new TextView(this);
        rs.setText("显示为「左时间 / 右课程名」的通知，点一下进入课表");
        rs.setTextColor(th.onSurfaceVariant);
        rs.setTextSize(11f);
        rs.setLineSpacing(0, 1.1f);
        rcol.addView(rs);
        remRow.addView(rcol);
        final View remDot = dotToggle(set.remind);
        remRow.addView(remDot);
        remRow.setClickable(true);
        remRow.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                set.remind = !set.remind;
                set.save(SettingsActivity.this);
                styleDot(remDot, set.remind, true);
                if (set.remind) requestNotifPermission();
                Reminder.reschedule(SettingsActivity.this);
            }
        });
        remCard.addView(remRow);

        LinearLayout leadRow = new LinearLayout(this);
        leadRow.setOrientation(LinearLayout.HORIZONTAL);
        leadRow.setPadding(0, dp(12), 0, 0);
        TextView leadLabel = new TextView(this);
        leadLabel.setText("提前");
        leadLabel.setTextColor(th.onSurfaceVariant);
        leadLabel.setTextSize(12.5f);
        leadLabel.setPadding(0, dp(7), dp(8), 0);
        leadRow.addView(leadLabel);
        final int[] leads = {5, 10, 15, 20, 30};
        for (int lv : leads) {
            final int minutes = lv;
            leadRow.addView(pickChip(lv + "分", set.remindMin == lv, new View.OnClickListener() {
                public void onClick(View v) {
                    set.remindMin = minutes;
                    set.save(SettingsActivity.this);
                    Reminder.reschedule(SettingsActivity.this);
                    rebuild();
                }
            }));
        }
        remCard.addView(leadRow);
        remCard.addView(support("Android 16 及以上有效（显示为系统的「实时更新」）；"
                + "未适配的机型会以普通消息横幅形式呈现。"
                + "另外精确到分钟需要系统「闹钟和提醒」权限，没有时提醒最多晚几分钟。"));
        body.addView(remCard);

        // ================= 数据
        body.addView(sectionLabel("数据"));
        LinearLayout dataCard = card();
        final View clearRow = listAction(R.drawable.ic_close, "清除课表数据", "只删应用内的课表，不动 PDF 和设置", null);
        clearRow.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { openCard(clearRow, buildClearContent()); }
        });
        dataCard.addView(clearRow);
        final View aboutRow = listAction(R.drawable.ic_sparkles, "关于", "TY课程表", null);
        aboutRow.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { openCard(aboutRow, buildAboutContent()); }
        });
        dataCard.addView(aboutRow);
        body.addView(dataCard);
        if (panelScroll != null && keepScroll > 0) {
            panelScroll.scrollTo(0, keepScroll);
            panelScroll.post(new Runnable() {
                public void run() { panelScroll.scrollTo(0, keepScroll); }
            });
        }
    }

    // ---------------------------------------------------------------- 组件

    /** 全应用统一圆角（跟随课表设置） */
    private int cr() { return Theme.clamp(set.corner, 4, 40); }
    private int crBtn() { return Theme.clamp(set.corner, 6, 28); }
    private int crCard() { return Theme.clamp(set.corner + 2, 8, 34); }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(14), dp(12), dp(14), dp(12));
        c.setBackground(round(Theme.pct(th.surfaceContainerLow, 100), crCard(), 0, 0));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        c.setLayoutParams(lp);
        return c;
    }

    private View itemTitle(int icon, String title, String sub) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        ImageView iv = new ImageView(this);
        iv.setImageResource(icon);
        iv.setColorFilter(th.primary);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(22), dp(22));
        ilp.rightMargin = dp(12);
        r.addView(iv, ilp);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(th.onSurface);
        t.setTextSize(15);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        col.addView(t);
        if (sub != null) {
            TextView s = new TextView(this);
            s.setText(sub);
            s.setTextColor(th.onSurfaceVariant);
            s.setTextSize(11.5f);
            col.addView(s);
        }
        r.addView(col);
        return r;
    }

    private View sectionLabel(String s) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextSize(13f);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextColor(th.primary);
        tv.setPadding(dp(4), dp(16), 0, dp(8));
        return tv;
    }

    private View support(String s) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextSize(11.5f);
        tv.setLineSpacing(0, 1.15f);
        tv.setTextColor(th.onSurfaceVariant);
        tv.setPadding(dp(2), dp(6), 0, 0);
        return tv;
    }

    private View support2(String s) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextSize(11.5f);
        tv.setTextColor(th.onSurfaceVariant);
        tv.setPadding(dp(4), 0, 0, dp(8));
        return tv;
    }

    private View swatch(final int color) {
        FrameLayout f = new FrameLayout(this);
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(Math.min(cr(), 18)));
        if (set.theme == color) g.setStroke(dp(3), th.onSurface);
        f.setBackground(g);
        if (set.theme == color) {
            ImageView ck = new ImageView(this);
            ck.setImageResource(R.drawable.ic_check);
            ck.setColorFilter(Theme.onColor(color));
            f.addView(ck, new FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER));
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(36), dp(36));
        lp.rightMargin = dp(10);
        f.setLayoutParams(lp);
        f.setClickable(true);
        f.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                set.theme = color;
                set.save(SettingsActivity.this);
                th = Theme.of(color);
                rootBox.setBackgroundColor(th.surface);
                buildShell();
            }
        });
        return f;
    }

    /**
     * 时间胶囊：课程用 secondaryContainer，休息用 tertiaryContainer（沿用原有的休息配色）。
     * 宽度固定 —— 开始时间 / 结束时间因此成为真正的两列，所有行都对齐（以你要的那一行为准）。
     */
    private TextView timeChip(String s, boolean brk, View.OnClickListener l) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextSize(13.5f);
        tv.setTextColor(brk ? th.onTertiaryContainer : th.onSecondaryContainer);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(4), dp(6), dp(4), dp(6));
        tv.setBackground(ripple(Theme.pct(brk ? th.onTertiaryContainer : th.onSecondaryContainer, 16),
                brk ? th.tertiaryContainer : th.secondaryContainer, crBtn(), 0, 0));
        tv.setOnClickListener(l);
        tv.setLayoutParams(new LinearLayout.LayoutParams(dp(COL_TIME_W),
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return tv;
    }

    /** 休息段的名字胶囊（点一下改名），配色与课程不同、但与原来的休息段一致 */
    private TextView breakNameChip(String name, View.OnClickListener l) {
        TextView tv = new TextView(this);
        tv.setText(name);
        tv.setTextSize(13.5f);
        tv.setTextColor(th.onTertiaryContainer);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setGravity(Gravity.CENTER);
        tv.setMaxWidth(dp(104));
        tv.setPadding(dp(10), dp(6), dp(10), dp(6));
        tv.setBackground(ripple(Theme.pct(th.onTertiaryContainer, 16), th.tertiaryContainer, crBtn(), 0, 0));
        tv.setTag(TAG_BREAK_NAME);
        tv.setOnClickListener(l);
        return tv;
    }

    /** 休息段行：自带时段，可分别改名、改开始/结束时间、删除 */
    private View buildBreakContent(final Settings.Break b) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(this);
        title.setText(labelOf(b) + "　" + set.breakTimeText(b));
        title.setTextColor(th.onTertiaryContainer);
        title.setTextSize(16);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        box.addView(title);

        TextView sub = new TextView(this);
        sub.setText("第 " + b.after + " 节之后 · 时间可以单独改；不占节次，只影响课表里这一段的显示");
        sub.setTextColor(th.onSurfaceVariant);
        sub.setTextSize(11.5f);
        sub.setPadding(0, dp(6), 0, dp(10));
        box.addView(sub);

        LinearLayout tr = new LinearLayout(this);
        tr.setOrientation(LinearLayout.HORIZONTAL);
        tr.setGravity(Gravity.CENTER_VERTICAL);
        int[] r = set.breakRange(b);
        final View sc = timeChip(Settings.fmt(r[0]), true, null);
        sc.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { openCard(sc, buildBreakTimeContent(b, true)); }
        });
        tr.addView(sc);
        TextView tilde = new TextView(this);
        tilde.setText("~");
        tilde.setTextColor(th.onSurfaceVariant);
        tilde.setPadding(dp(5), 0, dp(5), 0);
        tr.addView(tilde);
        final View ec = timeChip(Settings.fmt(r[1]), true, null);
        ec.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { openCard(ec, buildBreakTimeContent(b, false)); }
        });
        tr.addView(ec);
        TextView len = new TextView(this);
        len.setText("  " + Math.max(0, r[1] - r[0]) + "分");
        len.setTextSize(11f);
        len.setTextColor(th.onSurfaceVariant);
        tr.addView(len);
        box.addView(tr);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(16);
        row.setLayoutParams(rlp);
        row.addView(cardButton("改名", false, new View.OnClickListener() {
            public void onClick(View v) { swapContent(buildRenameBreakContent(b)); }
        }));
        View sp = new View(this);
        sp.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));
        row.addView(sp);
        row.addView(cardButton("删除", true, new View.OnClickListener() {
            public void onClick(View v) {
                closeCard(new Runnable() {
                    public void run() { removeBreak(b); }
                });
            }
        }));
        box.addView(row);
        Fonts.apply(box);
        return box;
    }

    /** 给休息段改名的输入卡片 */
    private View buildRenameBreakContent(final Settings.Break b) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(this);
        title.setText("这段时间叫什么？");
        title.setTextColor(th.onTertiaryContainer);
        title.setTextSize(16);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        box.addView(title);

        TextView sub = new TextView(this);
        sub.setText("例如：午休 / 晚饭 / 大课间");
        sub.setTextColor(th.onSurfaceVariant);
        sub.setTextSize(11.5f);
        sub.setPadding(0, dp(6), 0, dp(10));
        box.addView(sub);

        final EditText et = new EditText(this);
        et.setText(b.name == null ? "" : b.name);
        et.setSingleLine();
        et.setTextColor(th.onSurface);
        et.setTextSize(16);
        et.setGravity(Gravity.CENTER);
        et.setPadding(dp(14), dp(12), dp(14), dp(12));
        et.setBackground(round(Theme.pct(th.surfaceContainerHigh, 100), crBtn(), 0, 0));
        box.addView(et);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(16);
        row.setLayoutParams(rlp);
        row.addView(cardButton("返回", false, new View.OnClickListener() {
            public void onClick(View v) { swapContent(buildBreakContent(b)); }
        }));
        View sp = new View(this);
        sp.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));
        row.addView(sp);
        row.addView(cardButton("确定", true, new View.OnClickListener() {
            public void onClick(View v) {
                String v2 = et.getText().toString().trim();
                b.name = v2.isEmpty() ? "休息" : v2;
                set.save(SettingsActivity.this);
                closeCard(new Runnable() {
                    public void run() { updateBreakRow(b); }
                });
            }
        }));
        box.addView(row);
        Fonts.apply(box);
        return box;
    }

    private View listAction(int icon, String title, String sub, View.OnClickListener l) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(0, dp(10), 0, dp(10));
        r.setClickable(true);
        r.setOnClickListener(l);
        ImageView iv = new ImageView(this);
        iv.setImageResource(icon);
        iv.setColorFilter(th.primary);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(22), dp(22));
        ilp.rightMargin = dp(12);
        r.addView(iv, ilp);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(th.onSurface);
        t.setTextSize(14.5f);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        col.addView(t);
        TextView s = new TextView(this);
        s.setText(sub);
        s.setTextColor(th.onSurfaceVariant);
        s.setTextSize(11.5f);
        col.addView(s);
        r.addView(col);
        return r;
    }

    /** 实心/空心小圆点开关 */
    private View dotToggle(boolean on) {
        View dot = new View(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(22), dp(22));
        dot.setLayoutParams(lp);
        styleDot(dot, on, false);
        return dot;
    }

    private void styleDot(View dot, boolean on, boolean animate) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        if (on) {
            g.setColor(th.primary);
        } else {
            g.setColor(Theme.pct(th.surface, 0));
            g.setStroke(Math.max(1, dp(2)), Theme.pct(th.outline, 190));
        }
        dot.setBackground(g);
        if (animate) {
            dot.animate().cancel();
            dot.setScaleX(0.55f);
            dot.setScaleY(0.55f);
            dot.animate().scaleX(1f).scaleY(1f).setDuration(240)
                    .setInterpolator(new android.view.animation.OvershootInterpolator(2.4f)).start();
        }
    }

    private interface OnValue { void on(int v); }

    /** 带图标 + 标题 + 数值的 MD3 风格滑块 */
    private View sliderRow(int icon, String title, int value, final int min, final int max, String unit,
                           final OnValue cb) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(8), 0, dp(2));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        ImageView iv = new ImageView(this);
        iv.setImageResource(icon);
        iv.setColorFilter(th.primary);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(18), dp(18));
        ilp.rightMargin = dp(10);
        head.addView(iv, ilp);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(th.onSurface);
        t.setTextSize(13.5f);
        t.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(t);
        final TextView val = new TextView(this);
        val.setText(value + unit);
        val.setTextColor(th.primary);
        val.setTextSize(13f);
        val.setTypeface(Typeface.DEFAULT_BOLD);
        head.addView(val);
        box.addView(head);

        SeekBar sb = new SeekBar(this);
        sb.setMax(max - min);
        sb.setProgress(value - min);
        sb.setSplitTrack(false);
        GradientDrawable track = new GradientDrawable();
        track.setColor(Theme.pct(th.outlineVariant, 160));
        track.setCornerRadius(dp(Math.min(cr(), 3)));
        GradientDrawable prog = new GradientDrawable();
        prog.setColor(th.primary);
        prog.setCornerRadius(dp(Math.min(cr(), 3)));
        ClipDrawable clip = new ClipDrawable(prog, Gravity.LEFT, ClipDrawable.HORIZONTAL);
        LayerDrawable ld = new LayerDrawable(new Drawable[]{track, clip});
        ld.setId(0, android.R.id.background);
        ld.setId(1, android.R.id.progress);
        sb.setProgressDrawable(ld);
        GradientDrawable thumb = new GradientDrawable();
        thumb.setColor(th.primary);
        thumb.setCornerRadius(dp(Math.min(cr(), 8)));
        thumb.setSize(dp(18), dp(18));
        sb.setThumb(thumb);
        sb.setThumbOffset(dp(4));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                int v = p + min;
                val.setText(v + unit);
                cb.on(v);
            }
            public void onStartTrackingTouch(SeekBar s) {}
            public void onStopTrackingTouch(SeekBar s) {}
        });
        box.addView(sb);
        return box;
    }

    private ImageView iconButton(int res, int tint, final String hint, View.OnClickListener l) {
        ImageView iv = new ImageView(this);
        iv.setImageResource(res);
        iv.setColorFilter(tint);
        iv.setPadding(dp(10), dp(10), dp(10), dp(10));
        GradientDrawable mask = new GradientDrawable();
        mask.setShape(GradientDrawable.OVAL);
        mask.setColor(Color.WHITE);
        iv.setBackground(new RippleDrawable(ColorStateList.valueOf(Theme.pct(tint, 16)), null, mask));
        iv.setClickable(true);
        iv.setOnClickListener(l);
        iv.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) { toast(hint); return true; }
        });
        iv.setLayoutParams(new LinearLayout.LayoutParams(dp(42), dp(42)));
        return iv;
    }

    private View tonalButton(int icon, String text, View.OnClickListener l) {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.HORIZONTAL);
        b.setGravity(Gravity.CENTER_VERTICAL);
        b.setPadding(dp(14), dp(9), dp(16), dp(9));
        GradientDrawable content = new GradientDrawable();
        content.setColor(Theme.pct(th.secondaryContainer, 100));
        content.setCornerRadius(dp(crBtn()));
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(dp(crBtn()));
        b.setBackground(new RippleDrawable(
                ColorStateList.valueOf(Theme.pct(th.onSecondaryContainer, 18)), content, mask));
        ImageView iv = new ImageView(this);
        iv.setImageResource(icon);
        iv.setColorFilter(th.onSecondaryContainer);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(17), dp(17));
        ilp.rightMargin = dp(7);
        b.addView(iv, ilp);
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(th.onSecondaryContainer);
        tv.setTextSize(13f);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        b.addView(tv);
        b.setClickable(true);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(8);
        b.setLayoutParams(lp);
        return b;
    }

    private Drawable ripple(int rc, int bg, int radiusDp, int strokeDp, int strokeColor) {
        GradientDrawable content = new GradientDrawable();
        content.setColor(bg);
        content.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0) content.setStroke(strokeDp, strokeColor);
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(dp(radiusDp));
        return new RippleDrawable(ColorStateList.valueOf(rc), content, mask);
    }

    private GradientDrawable round(int color, int radiusDp, int strokeDp, int strokeColor) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0) g.setStroke(strokeDp, strokeColor);
        return g;
    }

    // ---------------------------------------------------------------- 交互
    //
    // 一条统一的规则：每加一行都是「构建 → prepareRowIn 压成 1px → 插进视图树 → animRowIn 展开」，
    // 每删一行都是「animRowsOut 收起 → 移除」，全程不整块重建，所以不会有空面板那一帧。

    /** 末尾加一节课（节次号取当前最大 +1） */
    private void addPeriod() {
        int n = set.nextNumber();
        int start = 8 * 60;
        if (!set.periods.isEmpty()) {
            Settings.Period last = set.periods.get(set.periods.size() - 1);
            start = Math.min(last.end + 10, 23 * 60);
        }
        Settings.Period np = new Settings.Period(n, start, Math.min(start + 45, 24 * 60 - 1));
        set.periods.add(np);
        syncTimeline();
        set.save(this);
        LinearLayout row = buildCourseRow(np);
        prepareRowIn(row);
        insertBeforeFooter(row);
        animRowIn(row);
    }

    /** 在第 anchor 节下面插一节课（比它大的节次整体 +1） */
    private void addCourseAfter(final Settings.Period anchor) {
        if (anchor == null) { addPeriod(); return; }
        final int n = anchor.n + 1;
        for (Settings.Period q : set.periods) if (q.n >= n) q.n++;
        for (Settings.Break b : set.breaks) if (b.after >= n) b.after++;
        Settings.Period np = new Settings.Period(n, anchor.end,
                Math.min(24 * 60 - 1, anchor.end + 45));
        set.periods.add(np);
        syncTimeline();
        set.save(this);
        if (periodBox == null) return;
        refreshAllRows();                       // 后面的节次号整体变了，原地刷新
        LinearLayout arow = findCourseRow(anchor);
        if (arow == null) { fillPeriods(); return; }
        LinearLayout row = buildCourseRow(np);
        prepareRowIn(row);
        periodBox.addView(row, periodBox.indexOfChild(arow) + 1);
        animRowIn(row);
    }

    /** 在某节下面插一段休息：时段默认取这一节下课到下一节上课之间的空档 */
    private void addBreakAfter(final Settings.Period anchor) {
        if (anchor == null) { addBreakAtEnd(); return; }
        int[] g = set.gapAround(anchor.n);
        Settings.Break nb = new Settings.Break(anchor.n, "休息", g[0], g[1]);
        set.breaks.add(nb);
        syncTimeline();
        set.save(this);
        insertBreakRow(nb);
    }

    /** 末尾加一段休息：时段接在最后一节下课之后 */
    private void addBreakAtEnd() {
        Settings.Period last = null;
        for (Settings.Period p : set.periods) if (last == null || p.start > last.start) last = p;
        if (last == null) {                    // 一节都没有：放在一天开始之前
            Settings.Break nb = new Settings.Break(0, "休息",
                    Math.max(0, set.firstStart() - 45), set.firstStart());
            set.breaks.add(nb);
            syncTimeline();
            set.save(this);
            insertBreakRow(nb);
            return;
        }
        addBreakAfter(last);
    }

    /** 把一段休息的行插到时间轴上该去的位置（按它自己的开始时间，插到第一行「开始得更晚」的前面） */
    private void insertBreakRow(Settings.Break nb) {
        if (periodBox == null) return;
        LinearLayout row = buildBreakRow(nb);
        prepareRowIn(row);
        int[] rg = set.breakRange(nb);
        int at = -1;
        for (int i = 0; i < periodBox.getChildCount(); i++) {
            View v = periodBox.getChildAt(i);
            if (TAG_FOOTER.equals(v.getTag())) break;
            int key = rowStartKey(v);
            if (key >= 0 && key > rg[0]) { at = i; break; }
        }
        if (at < 0) {
            insertBeforeFooter(row);
        } else {
            periodBox.addView(row, at);
        }
        animRowIn(row);
    }

    /** 一行在时间轴上的开始时间；不是作息行就返回 -1 */
    private int rowStartKey(View v) {
        Object o = v.getTag(TAG_COURSE);
        if (o instanceof Settings.Period) return ((Settings.Period) o).start;
        o = v.getTag(TAG_BREAK);
        if (o instanceof Settings.Break) return set.breakRange((Settings.Break) o)[0];
        return -1;
    }

    /** 删掉一节：弹确认卡片，确认后这一行就地收起（跟着它的休息段一起） */
    private void confirmRemoveCourse(final Settings.Period p, final View btn) {
        final LinearLayout frow = btn == null ? null : findRowView(btn);
        openCard(frow == null ? btn : frow, buildConfirmContent("删除第 " + courseNo(p) + " 节？",
                "只会删掉这一节自己（它时段里面的休息段会一起删），"
                        + "已导入的课程数据不受影响；后面的节次会自动往前顺延。",
                "删除", new Runnable() {
                    public void run() { removeCourse(p); }
                }));
    }

    /**
     * 删掉一节课。
     * 只带走**时段落在这一节里面**的休息段，其它休息段只看自己的时间，不会被连坐；
     * 删完按时间轴重排一次（节次号顺延），所以表现为「这一行消失、后面的行往上顶」。
     */
    private void removeCourse(final Settings.Period p) {
        if (p == null) return;
        // 要消失的行先从视图结构里找出来，再动数据
        //（之前在 remove 之后才 set.period(n) 找行，那时已经查不到，课程行就不会被移除）
        final List<View> gone = new ArrayList<View>();
        for (PItem it : allItems()) {
            if (it.p == p) {
                View row = findCourseRow(p);
                if (row != null) gone.add(row);
            } else if (it.b != null && breakInside(it.b, p)) {
                View row = findBreakRow(it.b);
                if (row != null) gone.add(row);
            }
        }
        set.periods.remove(p);
        for (int i = set.breaks.size() - 1; i >= 0; i--) {
            if (breakInside(set.breaks.get(i), p)) set.breaks.remove(i);
        }
        set.save(this);
        animRowsOut(gone, new Runnable() {
            public void run() { renumberCourses(); }
        });
    }

    /** 这段休息是不是落在这节课的时段里（只有这样才跟着这节课一起删） */
    private boolean breakInside(Settings.Break b, Settings.Period p) {
        if (b == null || p == null) return false;
        int[] rg = set.breakRange(b);
        return rg[0] >= p.start && rg[0] < p.end;
    }

    /** 删掉一段休息 */
    private void removeBreak(final Settings.Break b) {
        if (b == null) return;
        set.breaks.remove(b);
        set.save(this);
        animRowsOut(oneRow(findBreakRow(b)), null);
    }

    /**
     * 删掉一节之后：按时间轴重排节次号并原地刷新。
     * 休息段不问“挂在哪一节后面”，只看自己的时段，所以删课不会把它一并带走。
     */
    private void renumberCourses() {
        syncTimeline();
        // 节次号变了，key 也跟着变 —— 顺序记录按当前视图重写一遍，保持有效
        rebuildRowOrderFromViews();
        set.save(this);
        refreshAllRows();
    }

    private void resetPeriods(final View btn) {
        openCard(btn, buildConfirmContent("恢复默认作息？",
                "上午 8:00 开始、下午 14:00 开始、晚上 19:20 开始，共 13 节（每节 45 分钟）；第 6 节后是午休 13:30~14:00，第 11 节后是晚饭 18:40~19:20。你自己改过的时间会被这一套覆盖。",
                "恢复", new Runnable() {
                    public void run() {
                        Settings def = Settings.defaults();
                        set.periods.clear();
                        set.periods.addAll(def.periods);
                        set.breaks.clear();
                        set.breaks.addAll(def.breaks);
                        syncTimeline();
                        set.save(SettingsActivity.this);
                        rebuild();
                    }
                }));
    }

    private List<View> oneRow(View v) {
        List<View> l = new ArrayList<View>();
        if (v != null) l.add(v);
        return l;
    }

    /** 通用确认卡片：标题 + 说明 + 取消/确认按钮，确定后走 cardAction */
    private View buildConfirmContent(String title, String msg, String okLabel, final Runnable cardAction) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(th.primary);
        t.setTextSize(16);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        box.addView(t);

        TextView m = new TextView(this);
        m.setText(msg);
        m.setTextColor(th.onSurfaceVariant);
        m.setTextSize(12.5f);
        m.setLineSpacing(0, 1.15f);
        m.setPadding(0, dp(8), 0, dp(2));
        box.addView(m);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(16);
        row.setLayoutParams(rlp);
        row.addView(cardButton("取消", false, new View.OnClickListener() {
            public void onClick(View v) { closeCard(); }
        }));
        View sp = new View(this);
        sp.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));
        row.addView(sp);
        row.addView(cardButton(okLabel, true, new View.OnClickListener() {
            public void onClick(View v) {
                closeCard(new Runnable() {
                    public void run() { if (cardAction != null) cardAction.run(); }
                });
            }
        }));
        box.addView(row);
        Fonts.apply(box);
        return box;
    }




    private void pickFromBg() {
        Bitmap b = Bg.loadSrc(this, 480);
        if (b == null) {
            toast("还没有背景图，先选一张图片");
            return;
        }
        int c = Theme.extract(b, set.theme);
        set.theme = 0xFF000000 | (c & 0xFFFFFF);
        set.save(this);
        th = Theme.of(set.theme);
        rootBox.setBackgroundColor(th.surface);
        buildShell();
        toast("已取色 " + hex(set.theme));
    }

    private void pickImage() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivityForResult(i, REQ_IMG);
        } catch (Exception e) {
            toast("没有找到图片选择器");
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_IMG && res == RESULT_OK && data != null && data.getData() != null) {
            try {
                InputStream in = getContentResolver().openInputStream(data.getData());
                File f = Bg.srcFile(this);
                FileOutputStream out = new FileOutputStream(f);
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                out.close();
                in.close();
                Bg.markChanged();          // 换了原图：通知主界面背景要重新取
                set.bg = f.getName();
                set.save(this);
                // 直接进裁切：按当前屏幕比例取景，避免被拉伸变形
                Intent c = new Intent(this, CropActivity.class);
                c.putExtra(CropActivity.EXTRA_LANDSCAPE, Bg.isLandscape(this));
                startActivity(c);
                rebuild();
            } catch (Throwable t) {
                toast("读取图片失败：" + t.getMessage());
            }
        }
    }

    private View cropButton(final boolean landscape, String label, boolean current) {
        View b = tonalButton(R.drawable.ic_image, label + (Bg.hasCrop(this, landscape) ? " ✓" : ""),
                new View.OnClickListener() {
                    public void onClick(View v) {
                        Intent c = new Intent(SettingsActivity.this, CropActivity.class);
                        c.putExtra(CropActivity.EXTRA_LANDSCAPE, landscape);
                        startActivity(c);
                    }
                });
        return b;
    }

    // pickDate() 已移除：开学日期改用自绘卡片（buildDateContent），与课表其它弹窗同一套 UI


    // ------------------------------------------------ 关于 / 开源许可（和课程格子同款非线性展开）

    private void openCard(final View source, View content) {
        if (card != null) {
            // 卡片已经开着（比如在休息段卡片里点时间去改时间）：来源跟着换成新控件，
            // 这样收回时还是「从哪来回哪去」
            if (source != null) {
                cardSource = source;
                RectF r = rectOf(source);
                if (r != null) cardSourceRect = r;
            }
            swapContent(content);
            return;
        }
        cardSource = source;
        cardSourceRect = rectOf(source);
        if (cardSourceRect == null) cardSourceRect = new RectF(0, 0, dp(48), dp(48));
        final RectF from = new RectF(cardSourceRect);

        cardDim = new View(this);
        cardDim.setBackgroundColor(0x00000000);
        cardDim.setClickable(true);
        cardDim.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { closeCard(); }
        });
        overlay.addView(cardDim, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(round(th.surface, cr(), 0, 0));
        card.setClipToOutline(true);
        card.setElevation(dp(14));
        card.setPadding(dp(18), dp(16), dp(18), dp(14));
        card.addView(content);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                Math.max(1, Math.round(from.width())), Math.max(1, Math.round(from.height())));
        lp.leftMargin = Math.round(from.left);
        lp.topMargin = Math.round(from.top);
        card.setLayoutParams(lp);
        overlay.addView(card);

        final View contentRef = content;
        contentRef.setAlpha(0f);
        card.post(new Runnable() {
            public void run() {
                RectF to = cardTarget(contentRef);
                animateRect(from, to, true);
            }
        });
    }

    private RectF cardTarget(View content) {
        int rootW = Math.max(1, overlay.getWidth());
        int rootH = Math.max(1, overlay.getHeight());
        int tw = Math.min(rootW - dp(36), dp(420));
        card.measure(View.MeasureSpec.makeMeasureSpec(tw, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int thh = Math.min(card.getMeasuredHeight(), rootH - dp(48));
        float left = (rootW - tw) / 2f, top = (rootH - thh) / 2f;
        return new RectF(left, top, left + tw, top + thh);
    }

    /** 卡片里换内容（关于 ↔ 开源许可），带高度过渡和交叉淡入 */
    private void swapContent(View content) {
        if (card == null) return;
        final int fromH = card.getHeight();
        final RectF from = currentCardRect();
        card.removeAllViews();
        card.addView(content);
        content.setAlpha(0f);
        card.post(new Runnable() {
            public void run() {
                RectF to = cardTarget(content);
                swapAnim(from, to, fromH, content);
            }
        });
    }

    private void swapAnim(final RectF from, final RectF to, final int fromH, final View content) {
        ValueAnimator va = ValueAnimator.ofFloat(0f, 1f);
        va.setDuration(240);
        va.setInterpolator(new PathInterpolator(0.05f, 0.7f, 0.1f, 1f));
        va.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            public void onAnimationUpdate(ValueAnimator a) {
                float t = (Float) a.getAnimatedValue();
                float l = from.left + (to.left - from.left) * t;
                float tp = from.top + (to.top - from.top) * t;
                float r = from.right + (to.right - from.right) * t;
                float b = from.bottom + (to.bottom - from.bottom) * t;
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) card.getLayoutParams();
                lp.leftMargin = Math.round(l);
                lp.topMargin = Math.round(tp);
                lp.width = Math.max(1, Math.round(r - l));
                lp.height = Math.max(1, Math.round(b - tp));
                card.setLayoutParams(lp);
                content.setAlpha(Theme.clamp((t - 0.25f) / 0.75f, 0f, 1f));
                if (cardDim != null) cardDim.setBackgroundColor((Math.round(0x80 * t)) << 24);
            }
        });
        va.addListener(new Animator.AnimatorListener() {
            public void onAnimationStart(Animator a) {}
            public void onAnimationCancel(Animator a) {}
            public void onAnimationRepeat(Animator a) {}
            public void onAnimationEnd(Animator a) { content.setAlpha(1f); }
        });
        va.start();
    }

    private RectF currentCardRect() {
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) card.getLayoutParams();
        return new RectF(lp.leftMargin, lp.topMargin,
                lp.leftMargin + lp.width, lp.topMargin + lp.height);
    }

    /** 控件在 overlay 坐标系里的矩形；控件已脱离视图树时返回 null */
    private RectF rectOf(View v) {
        if (v == null || overlay == null || !v.isAttachedToWindow()) return null;
        int[] vl = new int[2], rl = new int[2];
        v.getLocationInWindow(vl);
        overlay.getLocationInWindow(rl);
        int w = Math.max(1, v.getWidth()), h = Math.max(1, v.getHeight());
        return new RectF(vl[0] - rl[0], vl[1] - rl[1], vl[0] - rl[0] + w, vl[1] - rl[1] + h);
    }

    private void closeCard() { closeCard(null); }

    /** 收卡片；after 会在卡片被移除的同一帧里执行，避免设置页闪一下 */
    private void closeCard(final Runnable after) {
        if (card == null) {
            if (after != null) after.run();
            return;
        }
        // 从哪来回哪去：优先控件当前的位置，控件被重建过就用打开时记下的位置
        RectF to = rectOf(cardSource);
        if (to == null) to = cardSourceRect;
        if (to == null) {
            float cx = card.getLeft() + card.getWidth() / 2f;
            float cy = card.getTop() + card.getHeight() / 2f;
            to = new RectF(cx - dp(24), cy - dp(24), cx + dp(24), cy + dp(24));
        }
        animateRect(currentCardRect(), clampToOverlay(to), false, after);
    }

    /** 目标矩形限制在窗口内，避免飞到屏幕外 */
    private RectF clampToOverlay(RectF r) {
        float w = Math.max(1, overlay.getWidth()), h = Math.max(1, overlay.getHeight());
        float cx = Theme.clamp(r.centerX(), 0, w);
        float cy = Theme.clamp(r.centerY(), 0, h);
        float hw = Math.min(r.width(), w) / 2f, hh = Math.min(r.height(), h) / 2f;
        return new RectF(cx - hw, cy - hh, cx + hw, cy + hh);
    }

    /** 和课程格子同一条 emphasized 曲线 */
    private void animateRect(final RectF from, final RectF to, final boolean expand) {
        animateRect(from, to, expand, null);
    }

    private void animateRect(final RectF from, final RectF to, final boolean expand, final Runnable after) {
        if (card == null) {
            if (after != null) after.run();
            return;
        }
        ValueAnimator va = ValueAnimator.ofFloat(0f, 1f);
        va.setDuration(expand ? 380 : 230);
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
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) card.getLayoutParams();
                lp.leftMargin = Math.round(l);
                lp.topMargin = Math.round(tp);
                lp.width = Math.max(1, Math.round(r - l));
                lp.height = Math.max(1, Math.round(b - tp));
                card.setLayoutParams(lp);
                if (card.getChildCount() > 0) {
                    card.getChildAt(0).setAlpha(expand
                            ? Theme.clamp((t - 0.35f) / 0.65f, 0f, 1f)
                            : Theme.clamp(1f - t / 0.5f, 0f, 1f));
                }
                if (cardDim != null) {
                    cardDim.setBackgroundColor((Math.round(0x80 * (expand ? t : 1f - t))) << 24);
                }
            }
        });
        va.addListener(new Animator.AnimatorListener() {
            public void onAnimationStart(Animator a) {}
            public void onAnimationCancel(Animator a) {}
            public void onAnimationRepeat(Animator a) {}
            public void onAnimationEnd(Animator a) {
                if (!expand && card != null) {
                    if (card.getParent() != null) ((ViewGroup) card.getParent()).removeView(card);
                    if (cardDim != null && cardDim.getParent() != null) {
                        ((ViewGroup) cardDim.getParent()).removeView(cardDim);
                    }
                    card = null;
                    cardDim = null;
                    cardSource = null;
                    cardSourceRect = null;
                    // 同一帧里刷新内容：中间不会出现“空面板”那一帧
                    if (after != null) after.run();
                }
            }
        });
        va.start();
    }

    // ------------------------------------------------ 自绘内容卡片（与整体风格一致，不用系统控件）

    private int curStart(Settings.Period p) { return p.start; }

    private int curEnd(Settings.Period p) { return p.end; }

    /** 作息表里第 i 行的统一模型：一节课或一段休息，两者同级、同尺寸、都能单独改时间 */
    private static class PItem {
        Settings.Period p;
        Settings.Break b;
        int sortKey;    // 开始时间（分钟）
        int seq;        // 结束时间（分钟），只用于开始时间相同时的次级判定
        boolean isBreak() { return b != null; }
    }

    // 行高固定，课程行和休息行才可能“以第八节为标准”严丝合缝地对齐
    private static final int ROW_MIN_H = 44;    // dp
    private static final int COL_LABEL = 68;    // dp：左侧名称列（名称 + 开始 + 结束 三列的起点都靠它定）
    private static final int COL_TIME_W = 56;   // dp：开始/结束时间两列的固定宽度
    private static final int COL_TILDE_W = 16;  // dp：中间那个「~」的固定宽度
    private static final int COL_BTN_W = 26;    // dp：行尾 ➕ / ✘ 的宽度
    private static final int COL_BTN_H = 26;    // dp

    /**
     * 按**时间顺序**列出所有行：一节课和一段休息都是时间轴上的一个区间，谁先开始谁在上面。
     * 所以改了某节课的开始/结束时间，它在列表里的位置会自己跟着变；
     * 休息段的位置也由它自己的时段决定，不再靠「挂在哪一节后面」这种死绑定。
     */
    private List<PItem> allItems() {
        return applyRowOrder(allItemsRaw());
    }

    /** 只按时间排序（不含手动顺序），顺序记录也由它生成 */
    private List<PItem> allItemsRaw() {
        List<PItem> l = new ArrayList<PItem>();
        for (Settings.Period p : set.periods) {
            PItem it = new PItem();
            it.p = p;
            it.sortKey = p.start;            // 结束时间（休憩落在课程时段里时用来定先后）
            it.seq = p.end;
            l.add(it);
        }
        for (Settings.Break b : set.breaks) {
            PItem it = new PItem();
            int[] rg = set.breakRange(b);
            it.sortKey = rg[0];              // 开始时间
            it.seq = rg[1];                  // 结束时间
            it.b = b;
            l.add(it);
        }
        Collections.sort(l, new Comparator<PItem>() {
            public int compare(PItem a, PItem b) {
                // ① 谁先开始谁在上面 —— 这是主判据
                if (a.sortKey != b.sortKey) return a.sortKey - b.sortKey;
                // ② 开始时间相同（比如休憩紧接在课程下课时开始）：时段先结束的排前面
                if (a.seq != b.seq) return a.seq - b.seq;
                // ③ 完全重合：课程在上、休憩在下
                return (a.b == null ? 0 : 1) - (b.b == null ? 0 : 1);
            }
        });
        return l;
    }

    private String keyOf(PItem it) {
        return it.b != null ? Settings.keyOf(it.b) : Settings.keyOf(it.p);
    }

    /** 按 set.rowOrder 里记的顺序重排；不在记录里的行保持「按时间」的相对位置，追加在后面 */
    private List<PItem> applyRowOrder(List<PItem> ordered) {
        if (set.rowOrder.isEmpty() || ordered.size() < 2) return ordered;
        List<PItem> out = new ArrayList<PItem>();
        boolean[] used = new boolean[ordered.size()];
        for (String k : set.rowOrder) {
            for (int i = 0; i < ordered.size(); i++) {
                if (used[i]) continue;
                if (!k.equals(keyOf(ordered.get(i)))) continue;
                out.add(ordered.get(i));
                used[i] = true;
                break;
            }
        }
        for (int i = 0; i < ordered.size(); i++) {
            if (!used[i]) out.add(ordered.get(i));
        }
        return out;
    }

    /** 按当前时间重排一次顺序记录（时间改过、或新加了行时调用） */
    private void refreshRowOrderFromTimeline() {
        set.rowOrder.clear();
        for (PItem it : allItemsRaw()) set.rowOrder.add(keyOf(it));
    }

    /**
     * 把休息段的 after（跟在第几节后面）按当前时间轴重新对齐，并把节次号重排成 1..N。
     * 节次号只是显示用的序号，真正的先后一律由时间决定，所以每次改完时间都调用它。
     */
    private void syncTimeline() {
        set.sort();
        int k = 0;
        for (Settings.Period p : set.periods) p.n = ++k;
        for (Settings.Break b : set.breaks) {
            int anchor = 0;
            for (Settings.Period p : set.periods) {
                int[] rg = set.breakRange(b);
                if (p.start < rg[0]) anchor = p.n;      // 开始时间在它之前的那一节
            }
            b.after = anchor;
        }
    }

    /** 一节课的显示序号（按当前顺序从 1 数起） */
    private int courseNo(Settings.Period p) {
        if (p == null) return 0;
        int k = 0;
        for (Settings.Period q : set.periods) {
            k++;
            if (q == p) return k;
        }
        return Math.max(1, p.n);
    }

    private String labelOf(Settings.Period p) { return "第" + courseNo(p) + "节"; }

    private String labelOf(Settings.Break b) {
        return b.name == null || b.name.trim().isEmpty() ? "休息" : b.name;
    }

    /**
     * 重建「上课时间」这一块。
     * 只在整页重建或「恢复默认」时用；日常的增/删/改一律原地动行，
     * 整块重建才会出现空面板那一帧。
     */
    private void fillPeriods() {
        if (periodBox == null) return;
        periodBox.setLayoutTransition(null);      // 批量重建期间不播换位动画，否则一屏乱弹
        periodBox.removeAllViews();
        List<PItem> items = allItems();
        for (int i = 0; i < items.size(); i++) {
            PItem it = items.get(i);
            periodBox.addView(it.isBreak() ? buildBreakRow(it.b) : buildCourseRow(it.p));
        }
        periodBox.addView(buildPeriodFooter());
        installReorderTransition();
    }

    /**
     * 拖动换位时的「挤开」动画：别的行平滑滑到新位置，而不是瞬间跳过去。
     * 只对位置变化（CHANGING）做动画 —— 增删有自己的展开/收起动画，不能在这里重复播。
     */
    private void installReorderTransition() {
        if (periodBox == null) return;
        LayoutTransition lt = new LayoutTransition();
        lt.setDuration(190);
        lt.setInterpolator(LayoutTransition.CHANGING,
                new PathInterpolator(0.2f, 0.9f, 0.25f, 1f));
        lt.disableTransitionType(LayoutTransition.APPEARING);
        lt.disableTransitionType(LayoutTransition.DISAPPEARING);
        lt.disableTransitionType(LayoutTransition.CHANGE_APPEARING);
        lt.disableTransitionType(LayoutTransition.CHANGE_DISAPPEARING);
        periodBox.setLayoutTransition(lt);
    }

    /**
     * 拖动期间把行画成一张半透明、略微抬起的位图，跟手移动，
     * 就像桌面拖图标那样 —— 原位留下的是被挤开的其它行。
     */
    private class RowShadow extends View.DragShadowBuilder {
        private final Bitmap bmp;
        RowShadow(View v) {
            super(v);
            bmp = snapshot(v);
        }
        @Override
        public void onProvideShadowMetrics(android.graphics.Point outShadowSize,
                                           android.graphics.Point outShadowTouchPoint) {
            if (bmp == null) { super.onProvideShadowMetrics(outShadowSize, outShadowTouchPoint); return; }
            outShadowSize.set(bmp.getWidth(), bmp.getHeight());
            outShadowTouchPoint.set(bmp.getWidth() / 2, bmp.getHeight() / 2);
        }
        @Override
        public void onDrawShadow(Canvas canvas) {
            if (bmp == null) { super.onDrawShadow(canvas); return; }
            canvas.drawBitmap(bmp, 0, 0, new android.graphics.Paint(0xE6FFFFFF));
        }
    }

    /** 把一行画成位图（放大一点点，做出「抬起来」的感觉） */
    private Bitmap snapshot(View v) {
        try {
            int w = Math.max(1, v.getWidth()), h = Math.max(1, v.getHeight());
            float sc = 1.03f;
            Bitmap raw = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(raw);
            v.draw(c);
            int nw = Math.max(1, Math.round(w * sc)), nh = Math.max(1, Math.round(h * sc));
            Bitmap out = Bitmap.createBitmap(nw, nh, Bitmap.Config.ARGB_8888);
            Canvas c2 = new Canvas(out);
            c2.drawBitmap(raw, null, new android.graphics.Rect(0, 0, nw, nh),
                    new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG));
            raw.recycle();
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 底部三个按钮：添加课程 / 添加休息 / 恢复默认。
     * 它永远都在（哪怕一节都不剩），否则删空之后就再也加不回来了。
     */
    private View buildPeriodFooter() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(10), 0, 0);
        box.setTag(TAG_FOOTER);

        LinearLayout adds = new LinearLayout(this);
        adds.setOrientation(LinearLayout.HORIZONTAL);
        adds.addView(footerButton(R.drawable.ic_add, "添加课程", false, new View.OnClickListener() {
            public void onClick(View v) { addPeriod(); }
        }));
        adds.addView(footerButton(R.drawable.ic_clock, "添加休息", true, new View.OnClickListener() {
            public void onClick(View v) { addBreakAtEnd(); }
        }));
        box.addView(adds);

        TextView reset = new TextView(this);
        reset.setText("恢复默认作息");
        reset.setTextSize(12f);
        reset.setTextColor(th.onSurfaceVariant);
        reset.setGravity(Gravity.CENTER);
        reset.setPadding(dp(10), dp(9), dp(10), dp(9));
        reset.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { resetPeriods(v); }
        });
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(4);
        box.addView(reset, rlp);
        return box;
    }

    /** 底部按钮：等分宽度，保证「添加课程 / 添加休息」都完整显示 */
    private View footerButton(int icon, String text, boolean brk, View.OnClickListener l) {
        LinearLayout b = new LinearLayout(this);
        b.setOrientation(LinearLayout.HORIZONTAL);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(6), dp(9), dp(6), dp(9));
        int bg = brk ? th.tertiaryContainer : th.secondaryContainer;
        int fg = brk ? th.onTertiaryContainer : th.onSecondaryContainer;
        GradientDrawable content = new GradientDrawable();
        content.setColor(Theme.pct(bg, 100));
        content.setCornerRadius(dp(crBtn()));
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(dp(crBtn()));
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(Theme.pct(fg, 18)), content, mask));
        ImageView iv = new ImageView(this);
        iv.setImageResource(icon);
        iv.setColorFilter(fg);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(16), dp(16));
        ilp.rightMargin = dp(5);
        b.addView(iv, ilp);
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(fg);
        tv.setTextSize(12.5f);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        b.addView(tv);
        b.setClickable(true);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.rightMargin = brk ? 0 : dp(8);
        b.setLayoutParams(lp);
        return b;
    }

    /** 右侧的两个按钮：➕ 在这行下面加内容（弹小卡片选加课还是加休息）、✘ 删掉这一行自己 */
    private void addRowButtons(LinearLayout row, final Settings.Period anchor, final Runnable onDelete) {
        final View[] holder = new View[1];
        View addBtn = rowActionButton(false, R.drawable.ic_add, "在这行下面加内容", new View.OnClickListener() {
            public void onClick(View v) { openCard(holder[0], buildAddMenuContent(anchor)); }
        });
        holder[0] = addBtn;
        row.addView(addBtn);
        row.addView(rowActionButton(true, R.drawable.ic_close, "删除这一行", new View.OnClickListener() {
            public void onClick(View v) { onDelete.run(); }
        }));
    }

    /** 行尾的小图标按钮；danger=true 时用弱化的前景色，避免和「+」抢视线 */
    private View rowActionButton(boolean danger, int icon, String hint, View.OnClickListener l) {
        ImageView iv = new ImageView(this);
        iv.setImageResource(icon);
        iv.setColorFilter(danger ? Theme.pct(th.onSurfaceVariant, 200) : th.primary);
        iv.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        iv.setContentDescription(hint);
        iv.setPadding(dp(3), dp(3), dp(3), dp(3));
        iv.setBackground(ripple(Theme.pct(th.onSurfaceVariant, 20), 0, dp(10), 0, 0));
        iv.setClickable(true);
        iv.setOnClickListener(l);
        // 宽度固定，课程行和休息行的这一列才对得齐
        iv.setLayoutParams(new LinearLayout.LayoutParams(dp(COL_BTN_W), dp(COL_BTN_H)));
        return iv;
    }

    /** 一节课的整行：节次 + 起止时间 + 时长 + ➕ ✘ */
    private LinearLayout buildCourseRow(final Settings.Period p) {
        LinearLayout r = rowShell();
        wrapRowBg(r);                              // 只有课程行有底色
        r.setTag("P" + p.n);
        r.setTag(TAG_COURSE, p);

        TextView no = new TextView(this);
        no.setId(R.id.period_no);
        no.setText(labelOf(p));
        no.setTextColor(th.onSurface);
        no.setTextSize(13.5f);
        no.setTypeface(Typeface.DEFAULT_BOLD);
        r.addView(labelColumn(no));

        View.OnClickListener onStart = new View.OnClickListener() {
            public void onClick(View v) { openCard(v, buildTimeContent(p, true)); }
        };
        View.OnClickListener onEnd = new View.OnClickListener() {
            public void onClick(View v) { openCard(v, buildTimeContent(p, false)); }
        };
        addTimeBlock(r, Settings.fmt(curStart(p)), Settings.fmt(curEnd(p)), p.minutes(), false,
                onStart, onEnd, r);

        addRowButtons(r, p, new Runnable() {
            public void run() { confirmRemoveCourse(p, null); }
        });
        return r;
    }

    /** 一段休息的整行：与课程行同样的尺寸与位置，但没有底色、名字可改、时间独立 */
    private LinearLayout buildBreakRow(final Settings.Break b) {
        LinearLayout r = rowShell();               // 休息行不加背景色
        r.setTag("B" + b.after);
        r.setTag(TAG_BREAK, b);

        final TextView nameChip = breakNameChip(labelOf(b), null);
        nameChip.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { openCard(nameChip, buildBreakContent(b)); }
        });
        r.addView(labelColumn(nameChip));

        int[] rg = set.breakRange(b);
        View.OnClickListener onStart = new View.OnClickListener() {
            public void onClick(View v) { openCard(v, buildBreakTimeContent(b, true)); }
        };
        View.OnClickListener onEnd = new View.OnClickListener() {
            public void onClick(View v) { openCard(v, buildBreakTimeContent(b, false)); }
        };
        addTimeBlock(r, Settings.fmt(rg[0]), Settings.fmt(rg[1]), Math.max(0, rg[1] - rg[0]), true,
                onStart, onEnd, r);

        addRowButtons(r, b.after <= 0 ? null : set.period(b.after), new Runnable() {
            public void run() { removeBreak(b); }
        });
        return r;
    }

    /**
     * 把名称放进固定宽度的列里（居中）。
     * 名称列宽度一致 + 时间块尺寸一致 ⇒ 「名称 / 开始时间 / 结束时间」三列在所有行里都对齐，
     * 也就和你要的「以第八节那一行为标准」一致。
     */
    private View labelColumn(View name) {
        FrameLayout col = new FrameLayout(this);
        FrameLayout.LayoutParams np = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        np.gravity = Gravity.CENTER;
        col.addView(name, np);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                dp(COL_LABEL), ViewGroup.LayoutParams.MATCH_PARENT);
        lp.rightMargin = dp(6);
        col.setLayoutParams(lp);
        return col;
    }

    /**
     * 两种行共用的外壳：同样的内边距、同样的最小高度、同样的行距。
     * 背景色不在这里加 —— 课程行自己带底色，休息行完全透明（不加背景色）。
     */
    private LinearLayout rowShell() {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(12), dp(7), dp(8), dp(7));
        // 高度取内容高度，但保底 ROW_MIN_H —— 课程行和休息行因此一样高
        r.setMinimumHeight(dp(ROW_MIN_H));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.bottomMargin = dp(6);
        r.setLayoutParams(rlp);
        attachDrag(r);   // 长按这一行可以上下拖动排序
        return r;
    }

    /**
     * 长按拖动排序：按住行内任意位置（含空白处、时间胶囊上）都能拖。
     * 拖动用系统拖放事件驱动 —— startDrag 之后原来的触摸流就被吃掉了，
     * 所以位置必须从 DragEvent 拿，不能指望 ACTION_MOVE。
     */
    private void attachDrag(final LinearLayout row) {
        row.setLongClickable(true);
        row.setOnLongClickListener(new View.OnLongClickListener() {
            public boolean onLongClick(View v) {
                beginDrag(row);
                return true;
            }
        });
    }

    /** 开始拖动这一行 */
    private void beginDrag(final LinearLayout row) {
        if (draggingRow != null || periodBox == null) return;
        Anim st = animState(row);
        if (st != null) st.finish(row);          // 正在展开/收起先收尾，别和拖动打架
        draggingRow = row;
        // 原位只留一个很淡的空位，跟手移动的是它放大后的位图
        row.setAlpha(0.30f);
        try { row.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS); } catch (Throwable ignore) {}
        setRowInteractive(row, false);           // 拖动期间关掉行内点击，长按结束时不会顺带打开时间卡片
        try {
            row.startDrag(android.content.ClipData.newPlainText("row", "row"),
                    new RowShadow(row), null, 0);
        } catch (Throwable t) {
            try {
                row.startDrag(android.content.ClipData.newPlainText("row", "row"),
                        new View.DragShadowBuilder(row), null, 0);
            } catch (Throwable ignore) {}
        }
    }

    /** 拖动中：手指划到哪一行，就把行挪到那个位置（实时换位，松手即是最终顺序） */
    private void onDragMove(float yInBox) {
        if (draggingRow == null || periodBox == null) return;
        int cur = periodBox.indexOfChild(draggingRow);
        int last = lastRowIndex();
        if (cur < 0 || last < 0) return;
        // 迟滞：行被动画平滑挪动的过程中，判定用的中点也在动，不留余量就会来回抖。
        // 往下要越过中点，往上要多越过 28% 行高才算数。
        int target = cur;
        for (int i = 0; i <= last; i++) {
            View v = periodBox.getChildAt(i);
            if (v == draggingRow) continue;
            int h = Math.max(1, v.getHeight());
            int top = v.getTop();
            if (yInBox >= top + h / 2) {
                target = i;                                  // 手指在它下半部分 → 落到它后面
            } else if (yInBox >= top + h * 0.22f) {
                target = i;                                  // 手指在它上半部分 → 落到它前面
                break;
            }
        }
        if (yInBox < periodBox.getChildAt(0).getTop()) target = 0;
        if (target == cur) return;
        // 一帧最多挪一行，避免手指飞快划过时位移动画全乱
        if (Math.abs(target - cur) > 1) target = cur + (target > cur ? 1 : -1);
        moveRowTo(cur, target);
    }

    /** 结束拖动：行恢复原样，新顺序记下来 */
    private void finishDrag() {
        final View row = draggingRow;
        if (row == null) return;
        draggingRow = null;
        setRowInteractive(row, true);
        // 落停：轻轻弹一下再归位，像图标落到格子里
        row.animate().cancel();
        row.setAlpha(0.30f);
        row.setScaleX(1.02f);
        row.animate().alpha(1f).scaleX(1f).setDuration(200)
                .setInterpolator(new OvershootInterpolator(1.3f)).start();
        persistRowOrder();
        renumberCourses();
        toast("顺序已保存");
    }

    /**
     * 把行挪到新位置。不做 requestLayout —— 这样位移由 LayoutTransition 的 CHANGING
     * 动画补上，其它行是「滑过去」而不是「跳过去」。
     */
    private void moveRowTo(int from, int to) {
        if (periodBox == null || from == to) return;
        View row = periodBox.getChildAt(from);
        if (row == null) return;
        periodBox.removeViewAt(from);
        periodBox.addView(row, Math.max(0, Math.min(to, periodBox.getChildCount())));
    }

    /** 拖到面板上下边缘时，带着列表慢慢滚，方便把行拖到看不见的位置 */
    private void dragAutoScroll(float yInBox) {
        if (panelScroll == null || periodBox == null) return;
        // periodBox 在滚动内容里的顶边，换算成「当前视口内的位置」
        int screenY = periodBox.getTop() + Math.round(yInBox) - panelScroll.getScrollY();
        int vh = panelScroll.getHeight();
        int step = dp(22);
        if (screenY < dp(56)) {
            panelScroll.smoothScrollBy(0, -step);
        } else if (screenY > vh - dp(56)) {
            panelScroll.smoothScrollBy(0, step);
        }
    }

    /** 最后一行作息行的下标（footer 不参与排序） */
    private int lastRowIndex() {
        if (periodBox == null) return -1;
        for (int i = periodBox.getChildCount() - 1; i >= 0; i--) {
            View v = periodBox.getChildAt(i);
            if (v.getTag(TAG_COURSE) != null || v.getTag(TAG_BREAK) != null) return i;
        }
        return -1;
    }

    /** 拖动期间临时开关行内点击 */
    private void setRowInteractive(View v, boolean on) {
        if (!(v instanceof ViewGroup)) return;
        ViewGroup g = (ViewGroup) v;
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            c.setClickable(on);
            if (c instanceof ViewGroup) setRowInteractive(c, on);
        }
    }

    /** 把当前的行顺序（按 key）写进设置 */
    private void persistRowOrder() {
        if (!rebuildRowOrderFromViews()) return;
        set.save(this);
    }

    /** 直接按视图里的行顺序重写顺序记录；视图里没有作息行时不动 */
    private boolean rebuildRowOrderFromViews() {
        if (periodBox == null) return false;
        List<String> keys = new ArrayList<String>();
        for (int i = 0; i < periodBox.getChildCount(); i++) {
            View v = periodBox.getChildAt(i);
            Object p = v.getTag(TAG_COURSE);
            if (p instanceof Settings.Period) {
                keys.add(Settings.keyOf((Settings.Period) p));
                continue;
            }
            Object b = v.getTag(TAG_BREAK);
            if (b instanceof Settings.Break) keys.add(Settings.keyOf((Settings.Break) b));
        }
        if (keys.isEmpty()) return false;
        set.rowOrder.clear();
        set.rowOrder.addAll(keys);
        return true;
    }

    /** 课程行的底色：用内层容器包住内容，底色只包住内容高度，不会糊成一条通栏 */
    private void wrapRowBg(LinearLayout row) {
        row.setBackground(round(Theme.pct(th.surfaceContainerLow, 100), set.corner, 0, 0));
    }

    /** 「开始 ~ 结束 时长」+ 一个把剩余空间推开的占位；两种行用的是同一套尺寸 */
    private void addTimeBlock(LinearLayout r, String s, String e, int minutes, boolean brk,
                              View.OnClickListener onStart, View.OnClickListener onEnd,
                              LinearLayout rowForTags) {
        final View sc = timeChip(s, brk, onStart);
        r.addView(sc);
        TextView tilde = new TextView(this);
        tilde.setText("~");
        tilde.setTextColor(th.onSurfaceVariant);
        tilde.setGravity(Gravity.CENTER);
        r.addView(tilde, new LinearLayout.LayoutParams(dp(COL_TILDE_W),
                ViewGroup.LayoutParams.WRAP_CONTENT));
        final View ec = timeChip(e, brk, onEnd);
        r.addView(ec);
        TextView len = new TextView(this);
        len.setText(" " + minutes + "分");
        len.setTextSize(11f);
        len.setTextColor(th.onSurfaceVariant);
        r.addView(len);
        rowForTags.setTag(TAG_CHIPS, new TextView[]{(TextView) sc, (TextView) ec, len});

        View sp = new View(this);
        sp.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));
        r.addView(sp);
    }

    /** 点「+」弹出的小卡片：选在这行下面加一节课还是加一段休息 */
    private View buildAddMenuContent(final Settings.Period anchor) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(this);
        title.setText("在这行下面加什么？");
        title.setTextColor(th.primary);
        title.setTextSize(16);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        box.addView(title);

        TextView sub = new TextView(this);
        sub.setText(anchor == null ? "加在最前面"
                : ("加在" + labelOf(anchor) + "的下面"));
        sub.setTextColor(th.onSurfaceVariant);
        sub.setTextSize(11.5f);
        sub.setPadding(0, dp(6), 0, dp(10));
        box.addView(sub);

        TextView course = menuRow("课程", "一节课，占了节次号", false);
        course.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                closeCard(new Runnable() {
                    public void run() { addCourseAfter(anchor); }
                });
                // 弹窗的出场动画与新课的进场动画同一条时间线，看起来是「卡片收回去、课上顶出来」
            }
        });
        box.addView(course);

        TextView brk = menuRow("休息", "不占节次，可以改名、单独设时段", true);
        brk.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                closeCard(new Runnable() {
                    public void run() { addBreakAfter(anchor); }
                });
            }
        });
        box.addView(brk);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(10);
        row.setLayoutParams(rlp);
        row.addView(cardButton("取消", false, new View.OnClickListener() {
            public void onClick(View v) { closeCard(); }
        }));
        box.addView(row);
        Fonts.apply(box);
        return box;
    }

    /** 小卡片里的一行选项 */
    private TextView menuRow(String title, String sub, boolean brk) {
        TextView tv = new TextView(this);
        tv.setText(title + "　" + sub);
        tv.setTextSize(14);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextColor(brk ? th.onTertiaryContainer : th.onSecondaryContainer);
        tv.setGravity(Gravity.CENTER_VERTICAL);
        tv.setPadding(dp(14), dp(12), dp(14), dp(12));
        tv.setBackground(ripple(Theme.pct(brk ? th.onTertiaryContainer : th.onSecondaryContainer, 16),
                brk ? th.tertiaryContainer : th.secondaryContainer, crBtn(), 0, 0));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        tv.setLayoutParams(lp);
        return tv;
    }

    // ------------------------------------------------- 作息行的「从上到下」展开/收起动画

    private static final int TAG_ROW_ANIM = 0x7f010000;   // 行上的动画状态（Anim 对象）
    private static final int TAG_CHIPS = 0x7f010001;      // 行内的 开始/结束/时长 三个 TextView
    private static final int TAG_COURSE = 0x7f010002;     // 行对应的 Period
    private static final int TAG_BREAK = 0x7f010003;      // 行对应的 Break
    private static final String TAG_BREAK_NAME = "ty_break_name";   // 休息行里的名字胶囊
    private static final String TAG_FOOTER = "FOOTER";    // 「添加课程 / 添加休息 / 恢复默认」那一行

    private LinearLayout findCourseRow(Settings.Period p) {
        if (p == null || periodBox == null) return null;
        for (int i = 0; i < periodBox.getChildCount(); i++) {
            View v = periodBox.getChildAt(i);
            if (v.getTag(TAG_COURSE) == p) return (LinearLayout) v;
        }
        return null;
    }

    private LinearLayout findBreakRow(Settings.Break b) {
        if (b == null || periodBox == null) return null;
        for (int i = 0; i < periodBox.getChildCount(); i++) {
            View v = periodBox.getChildAt(i);
            if (v.getTag(TAG_BREAK) == b) return (LinearLayout) v;
        }
        return null;
    }

    /** 从行内的某个子控件反查它所在的那一行 */
    private LinearLayout findRowView(View child) {
        if (child == null || periodBox == null) return null;
        for (int i = 0; i < periodBox.getChildCount(); i++) {
            View v = periodBox.getChildAt(i);
            if (v == child) return (LinearLayout) v;
            if (v instanceof ViewGroup && ((ViewGroup) v).indexOfChild(child) >= 0) return (LinearLayout) v;
        }
        return null;
    }

    /** 把新行插到「添加课程 / 添加休息 / 恢复默认」那一行之前 */
    private void insertBeforeFooter(View row) {
        if (periodBox == null) return;
        for (int i = 0; i < periodBox.getChildCount(); i++) {
            if (TAG_FOOTER.equals(periodBox.getChildAt(i).getTag())) {
                periodBox.addView(row, i);
                return;
            }
        }
        periodBox.addView(row);
    }

    /** 插到最前面（一节都还没有时用） */
    private void insertRowAtTop(View row) {
        if (periodBox == null) return;
        periodBox.addView(row, 0);
    }

    /** 原地刷新一节课那一行：节次号、起止时间、时长 */
    private void updatePeriodRow(int n) {
        Settings.Period p = set.period(n);
        LinearLayout row = findCourseRow(p);
        if (row == null) return;
        TextView no = row.findViewById(R.id.period_no);
        if (no != null) no.setText(labelOf(p));
        Object o = row.getTag(TAG_CHIPS);
        if (o instanceof TextView[]) {
            TextView[] c = (TextView[]) o;
            c[0].setText(Settings.fmt(curStart(p)));
            c[1].setText(Settings.fmt(curEnd(p)));
            c[2].setText(" " + p.minutes() + "分");
        }
        if (set.breakOf(n) != null) updateBreakRow(set.breakOf(n));   // 空档变了，休息行也跟着刷新
    }

    /** 原地刷新一段休息那一行：名字、起止时间、时长 */
    private void updateBreakRow(Settings.Break b) {
        LinearLayout row = findBreakRow(b);
        if (row == null || b == null) return;
        TextView name = row.findViewWithTag(TAG_BREAK_NAME);
        if (name != null) name.setText(labelOf(b));
        int[] rg = set.breakRange(b);
        Object o = row.getTag(TAG_CHIPS);
        if (o instanceof TextView[]) {
            TextView[] c = (TextView[]) o;
            c[0].setText(Settings.fmt(rg[0]));
            c[1].setText(Settings.fmt(rg[1]));
            c[2].setText(" " + Math.max(0, rg[1] - rg[0]) + "分");
        }
    }

    /** 休息段改名之后，重新算一下它后面那节课的「空档」相关文字 */
    private void refreshAllRows() {
        if (periodBox == null || !shellBuilt) return;
        for (Settings.Period p : set.periods) updatePeriodRow(p.n);
        for (Settings.Break b : set.breaks) updateBreakRow(b);
    }

    private LinearLayout findBreakRowByAnchor(int n) {
        if (periodBox == null) return null;
        for (int i = 0; i < periodBox.getChildCount(); i++) {
            View v = periodBox.getChildAt(i);
            if (!(v instanceof LinearLayout)) continue;
            if (!String.valueOf(v.getTag()).equals("B" + n)) continue;
            return (LinearLayout) v;
        }
        return null;
    }

    /**
     * 插入视图树之前先把这一行压成 1px 高、透明。
     * 这样它进入视图树的第一帧就是「没有」，后面接展开动画就不会先闪一下满高的行。
     */
    /**
     * 行动画的状态标记。
     * 不能靠 getTag() 的字符串来判定“动画还在不在”——
     * 收尾时会把标记清掉，而清除后可能还有一帧回调进来，那一帧就会被误判成“被打断”，
     * 结果既不继续缩小、也不移除视图：表现就是“删了课但那一行还在，重开设置才消失”。
     * 这里改成一个不会被外部篡改的状态对象，由它自己负责收尾。
     */
    private static class Anim {
        int full;               // 这一行的完整高度
        boolean running;
        ValueAnimator animator;
        boolean rowIn;

        /** 收尾：把这一行恢复到正常状态（WRAP_CONTENT / 不透明 / 无高度残留） */
        void finish(View row) {
            if (animator != null) animator.cancel();
            animator = null;
            running = false;
            if (!(row.getLayoutParams() instanceof LinearLayout.LayoutParams)) return;
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) row.getLayoutParams();
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            lp.bottomMargin = dp6(row);
            row.setLayoutParams(lp);
            row.setAlpha(1f);
        }

        private static int dp6(View v) {
            return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 6,
                    v.getResources().getDisplayMetrics());
        }
    }

    private Anim animState(View row) {
        Object o = row.getTag(TAG_ROW_ANIM);
        return o instanceof Anim ? (Anim) o : null;
    }

    /** 这一行的完整高度：优先用测量值，其次用当前高度 */
    private int measureRowHeight(final View row) {
        if (row == null) return dp(ROW_MIN_H);
        if (periodBox != null && periodBox.getWidth() > 0) {
            row.measure(View.MeasureSpec.makeMeasureSpec(periodBox.getWidth(), View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            int h = row.getMeasuredHeight();
            if (h > 0) return Math.max(dp(ROW_MIN_H), h);
        }
        int h = row.getHeight();
        return h > 0 ? h : dp(ROW_MIN_H);
    }

    /**
     * 插入视图树之前先把这一行压成 1px 高、透明。
     * 这样它进入视图树的第一帧就是「没有」，后面接展开动画就不会先闪出满高的行。
     */
    private Anim prepareRowIn(View row) {
        Anim st = new Anim();
        st.full = measureRowHeight(row);
        st.rowIn = true;
        row.setTag(TAG_ROW_ANIM, st);
        if (!set.anim) return st;
        if (!(row.getLayoutParams() instanceof LinearLayout.LayoutParams)) return st;
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) row.getLayoutParams();
        lp.height = 1;
        lp.bottomMargin = 0;
        row.setLayoutParams(lp);
        row.setAlpha(0f);
        return st;
    }

    /** 新行加在可见区域外面时把它轻轻带进视野，让用户看得见刚加的那一行 */
    private void ensureRowVisible(final View row) {
        if (row == null || panelScroll == null || !set.anim) return;
        row.post(new Runnable() {
            public void run() {
                if (row.getParent() == null || panelScroll == null) return;
                int vh = panelScroll.getHeight();
                if (vh <= 0) return;
                int[] rl = new int[2], sl = new int[2];
                row.getLocationInWindow(rl);
                panelScroll.getLocationInWindow(sl);
                // 换算到「滚动内容坐标系」：两个 getLocationInWindow 相减已经把 periodBox
                // 相对 body 的偏移算进去了，直接拿来用才是对的
                int top = rl[1] - sl[1] + panelScroll.getScrollY();
                int bot = top + Math.max(1, row.getHeight());
                int cur = panelScroll.getScrollY();
                int target;
                if (bot > cur + vh - dp(12)) {
                    target = bot - vh + dp(16);              // 在下方：往下带一点
                } else if (top < cur + dp(12)) {
                    target = top - dp(16);                   // 在上方：往上带一点
                } else {
                    return;                                  // 本来就在视野里，一律不动
                }
                if (target < 0) return;
                if (Math.abs(target - cur) > vh) return;     // 幅度离谱就放弃，绝不整段跳
                panelScroll.smoothScrollTo(0, target);
            }
        });
    }

    /** 新加进来的行：高度从 1px 长到完整高度（emphasized 曲线），旧行原地不动，所以视觉上是从上往下展开 */
    private void animRowIn(final View row) {
        if (row == null) return;
        Anim st = animState(row);
        if (st == null || st.running) return;          // 已经在跑就别叠加
        ensureRowVisible(row);
        if (!set.anim || !(row.getLayoutParams() instanceof LinearLayout.LayoutParams)) {
            st.full = 0;
            return;
        }
        final LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) row.getLayoutParams();
        final int full = st.full <= 0 ? measureRowHeight(row) : st.full;
        st.full = full;
        st.running = true;
        ValueAnimator va = ValueAnimator.ofFloat(0f, 1f);
        st.animator = va;
        va.setDuration(260);
        va.setInterpolator(new PathInterpolator(0.05f, 0.7f, 0.1f, 1f));
        va.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            public void onAnimationUpdate(ValueAnimator a) {
                if (!st.running) return;
                float t = (Float) a.getAnimatedValue();
                lp.height = Math.max(1, Math.round(full * t));
                lp.bottomMargin = Math.round(dp(6) * t);
                row.setLayoutParams(lp);
                row.setAlpha(Theme.clamp(t / 0.55f, 0f, 1f));
            }
        });
        va.addListener(new Animator.AnimatorListener() {
            public void onAnimationStart(Animator a) {}
            public void onAnimationCancel(Animator a) {}
            public void onAnimationRepeat(Animator a) {}
            public void onAnimationEnd(Animator a) {
                if (st.animator != a) return;          // 已被新动画接管
                st.finish(row);
            }
        });
        va.start();
    }

    /** 要删掉的行：高度收到 0 之后再真正移除；after 在同一帧里跑（不做整块重建，所以不会闪） */
    private void animRowsOut(final List<View> rows, final Runnable after) {
        final List<View> list = new ArrayList<View>();
        for (View v : rows) {
            if (v == null || list.contains(v)) continue;
            list.add(v);
        }
        if (list.isEmpty()) { if (after != null) after.run(); return; }
        if (!set.anim) {
            for (View v : list) removeRow(v);
            if (after != null) after.run();
            return;
        }
        // 先把还在跑展开动画的行收回到完整高度，避免用测量到的 1px 去“收起”（那样看不见变化）
        final int[] full = new int[list.size()];
        for (int i = 0; i < list.size(); i++) {
            View v = list.get(i);
            Anim st = animState(v);
            if (st != null) {
                if (st.animator != null) st.animator.cancel();
                st.running = false;
                if (st.full > 0) {
                    LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) v.getLayoutParams();
                    lp.height = st.full;
                    lp.bottomMargin = dp(6);
                    v.setLayoutParams(lp);
                    v.setAlpha(1f);
                }
                full[i] = st.full > 0 ? st.full : Math.max(1, v.getHeight());
            } else {
                full[i] = Math.max(1, v.getHeight());
                if (full[i] <= 1) full[i] = measureRowHeight(v);
            }
        }
        ValueAnimator va = ValueAnimator.ofFloat(0f, 1f);
        va.setDuration(200);
        va.setInterpolator(new PathInterpolator(0.4f, 0f, 0.6f, 0.2f));
        va.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            public void onAnimationUpdate(ValueAnimator a) {
                float t = (Float) a.getAnimatedValue();
                for (int i = 0; i < list.size(); i++) {
                    View v = list.get(i);
                    if (!(v.getLayoutParams() instanceof LinearLayout.LayoutParams)) continue;
                    LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) v.getLayoutParams();
                    lp.height = Math.max(0, Math.round(full[i] * (1f - t)));
                    lp.bottomMargin = Math.round(dp(6) * (1f - t));
                    v.setLayoutParams(lp);
                    v.setAlpha(1f - t);
                }
            }
        });
        va.addListener(new Animator.AnimatorListener() {
            public void onAnimationStart(Animator a) {}
            public void onAnimationCancel(Animator a) {}
            public void onAnimationRepeat(Animator a) {}
            public void onAnimationEnd(Animator a) {
                for (View v : list) removeRow(v);
                if (after != null) after.run();
            }
        });
        va.start();
    }

    /** 明确地把一行从视图树里摘掉，并把它的动画状态清干净 */
    private void removeRow(View v) {
        if (v == null) return;
        Anim st = animState(v);
        if (st != null) {
            if (st.animator != null) st.animator.cancel();
            st.running = false;
            v.setTag(TAG_ROW_ANIM, null);
        }
        v.setAlpha(1f);
        if (v.getParent() instanceof ViewGroup) ((ViewGroup) v.getParent()).removeView(v);
    }

    /** 时间选择：小时 + 分钟胶囊，实时预览 */
    private View buildTimeContent(final Settings.Period p, final boolean isStart) {
        return buildTimeCard(Settings.fmt(curStart(p)), Settings.fmt(curEnd(p)),
                labelOf(p) + " · " + (isStart ? "开始时间" : "结束时间"), null,
                isStart ? curStart(p) : curEnd(p), th.primary, false,
                new OnTime() {
                    public void save(int value) { stageTime(p, isStart, value); }
                });
    }

    /** 休息段的开始/结束时间：与课程用的是同一张时间卡片，只是配色不同、时间独立 */
    private View buildBreakTimeContent(final Settings.Break b, final boolean isStart) {
        int[] rg = set.breakRange(b);
        return buildTimeCard(Settings.fmt(rg[0]), Settings.fmt(rg[1]),
                labelOf(b) + " · " + (isStart ? "开始时间" : "结束时间"),
                "这段休息不占节次，时间只影响课表里这一段的显示",
                isStart ? rg[0] : rg[1], th.onTertiaryContainer, true,
                new OnTime() {
                    public void save(int value) { stageBreakTime(b, isStart, value); }
                });
    }

    private interface OnTime { void save(int value); }

    /** 时间卡片本体：大预览 + 时/分胶囊 + 取消/确定 */
    private View buildTimeCard(String startText, String endText, String title, String note,
                               int initial, int accent, boolean brk, final OnTime onOk) {
        final int[] cur = new int[]{initial};
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextColor(accent);
        titleView.setTextSize(16);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        box.addView(titleView);

        final TextView preview = new TextView(this);
        preview.setTextSize(30);
        preview.setTypeface(Typeface.DEFAULT_BOLD);
        preview.setTextColor(th.onSurface);
        preview.setGravity(Gravity.CENTER);
        preview.setPadding(0, dp(12), note == null ? 0 : dp(12), note == null ? dp(12) : dp(4));
        preview.setText(Settings.fmt(cur[0]));
        box.addView(preview);

        final TextView hint = new TextView(this);
        hint.setTextColor(th.onSurfaceVariant);
        hint.setTextSize(11.5f);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, 0, 0, dp(10));
        box.addView(hint);

        box.addView(smallLabel("时"));
        final LinearLayout hourRow = new LinearLayout(this);
        hourRow.setOrientation(LinearLayout.HORIZONTAL);
        HorizontalScrollView hs1 = new HorizontalScrollView(this);
        hs1.setHorizontalScrollBarEnabled(false);
        hs1.addView(hourRow);
        box.addView(hs1);

        box.addView(smallLabel("分"));
        final LinearLayout minRow = new LinearLayout(this);
        minRow.setOrientation(LinearLayout.HORIZONTAL);
        HorizontalScrollView hs2 = new HorizontalScrollView(this);
        hs2.setHorizontalScrollBarEnabled(false);
        hs2.addView(minRow);
        box.addView(hs2);

        final List<View> hourChips = new ArrayList<View>();
        final List<View> minChips = new ArrayList<View>();
        final String st = startText, en = endText;
        for (int h = 0; h < 24; h++) {
            final int hh = h;
            View c = pickChip(String.valueOf(h), (cur[0] / 60) == h, brk, new View.OnClickListener() {
                public void onClick(View v) {
                    cur[0] = hh * 60 + cur[0] % 60;
                    syncTimeCard(preview, hint, st, en, cur[0], hourChips, minChips, brk, note != null);
                }
            });
            hourChips.add(c);
            hourRow.addView(c);
        }
        for (int m = 0; m < 60; m += 5) {
            final int mm = m;
            View c = pickChip(String.format("%02d", m), (cur[0] % 60) == m, brk,
                    new View.OnClickListener() {
                        public void onClick(View v) {
                            cur[0] = (cur[0] / 60) * 60 + mm;
                            syncTimeCard(preview, hint, st, en, cur[0], hourChips, minChips, brk, note != null);
                        }
                    });
            minChips.add(c);
            minRow.addView(c);
        }

        syncTimeCard(preview, hint, st, en, cur[0], hourChips, minChips, brk, note != null);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(16);
        row.setLayoutParams(rlp);
        row.addView(cardButton("取消", false, new View.OnClickListener() {
            public void onClick(View v) { closeCard(); }
        }));
        View sp = new View(this);
        sp.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));
        row.addView(sp);
        row.addView(cardButton("确定", true, new View.OnClickListener() {
            public void onClick(View v) {
                onOk.save(cur[0]);
                closeCard();
            }
        }));
        box.addView(row);
        Fonts.apply(box);
        return box;
    }

    private View smallLabel(String t) {
        TextView tv = new TextView(this);
        tv.setText(t);
        tv.setTextColor(th.onSurfaceVariant);
        tv.setTextSize(11.5f);
        tv.setPadding(dp(2), dp(8), 0, dp(4));
        return tv;
    }

    private View pickChip(String label, boolean selected, View.OnClickListener l) {
        return pickChip(label, selected, false, l);
    }

    /** 选项胶囊；brk=true 时用休息段的 tertiary 配色 */
    private View pickChip(String label, boolean selected, boolean brk, View.OnClickListener l) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(13);
        tv.setTypeface(selected ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(12), dp(7), dp(12), dp(7));
        tv.setTextColor(selected
                ? (brk ? th.onTertiaryContainer : th.onSecondaryContainer) : th.onSurfaceVariant);
        tv.setBackground(round(selected
                ? Theme.pct(brk ? th.tertiaryContainer : th.secondaryContainer, 100)
                : Theme.pct(th.surfaceContainerHigh, 100), crBtn(), 0, 0));
        tv.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(6);
        tv.setLayoutParams(lp);
        return tv;
    }

    /** 时间卡片里的实时预览与提示 */
    private void syncTimeCard(TextView preview, TextView hint, String startText, String endText,
                              int value, List<View> hourChips, List<View> minChips,
                              boolean brk, boolean isBreakRow) {
        preview.setText(Settings.fmt(value));
        int h = value / 60, m = value % 60;
        for (int i = 0; i < hourChips.size(); i++) paintChip(hourChips.get(i), i == h, brk);
        for (int i = 0; i < minChips.size(); i++) paintChip(minChips.get(i), i * 5 == m, brk);
        if (isBreakRow) {
            hint.setText("当前这段 " + startText + "~" + endText + "，休息段不占节次");
        } else {
            hint.setText("当前 " + startText + "~" + endText
                    + (value < parseMin(endText) ? "" : "（结束需晚于开始）"));
        }
    }

    private int parseMin(String t) {
        try {
            String[] p = t.split(":");
            return Integer.parseInt(p[0]) * 60 + Integer.parseInt(p[1]);
        } catch (Exception e) {
            return 0;
        }
    }

    private void paintChip(View chip, boolean selected) {
        paintChip(chip, selected, false);
    }

    private void paintChip(View chip, boolean selected, boolean brk) {
        if (!(chip instanceof TextView)) return;
        TextView tv = (TextView) chip;
        tv.setTypeface(selected ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        tv.setTextColor(selected
                ? (brk ? th.onTertiaryContainer : th.onSecondaryContainer) : th.onSurfaceVariant);
        tv.setBackground(round(selected
                ? Theme.pct(brk ? th.tertiaryContainer : th.secondaryContainer, 100)
                : Theme.pct(th.surfaceContainerHigh, 100), crBtn(), 0, 0));
    }

    /** 改完立刻保存，并且只更新那一行的时间文字（不重建界面，所以不会闪） */
    private void stageTime(Settings.Period p, boolean isStart, int value) {
        int st = p.start, en = p.end;
        if (isStart) {
            int len = Math.max(30, en - st);      // 改开始：时长不变，结束整体顺延
            st = value;
            en = Math.min(24 * 60 - 1, value + len);
        } else {
            en = Math.max(value, st + 5);
        }
        p.start = st;
        p.end = en;
        set.save(this);
        // 时间变了 ⇒ 这一节在列表里的先后位置可能也变了，重排一次再重建
        // （重建会保留当前滚动位置，所以不会跳）
        syncTimeline();
        refreshRowOrderFromTimeline();
        rebuild();
        toast("已保存 " + Settings.fmt(st) + "~" + Settings.fmt(en));
    }

    /** 休息段的开始/结束时间：和其它行一样就地保存、原地刷新；没定过时段的先落地成实际值 */
    private void stageBreakTime(Settings.Break b, boolean isStart, int value) {
        if (b == null) return;
        int[] rg = set.breakRange(b);
        int st = rg[0], en = rg[1];
        if (isStart) {
            int len = Math.max(5, en - st);
            st = Math.min(value, 24 * 60 - 1);
            en = Math.min(24 * 60 - 1, st + len);
        } else {
            en = Math.max(value, st + 5);
        }
        b.start = st;
        b.end = en;
        set.save(this);
        syncTimeline();
        refreshRowOrderFromTimeline();
        rebuild();
        toast("已保存 " + Settings.fmt(st) + "~" + Settings.fmt(en));
    }

    /** 开学日期选择：年月日胶囊 + 大预览（与课程时间同一套 UI） */
    private View buildDateContent() {
        final Calendar cal = Calendar.getInstance();
        try { cal.setTime(Settings.SDF.parse(set.startDate)); } catch (Exception ignore) {}
        final int[] cur = new int[]{cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH)};

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(this);
        title.setText("开学日期（第 1 周周一）");        title.setTextColor(th.primary);
        title.setTextSize(16);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        box.addView(title);

        final TextView preview = new TextView(this);
        preview.setTextSize(30);
        preview.setTypeface(Typeface.DEFAULT_BOLD);
        preview.setTextColor(th.onSurface);
        preview.setGravity(Gravity.CENTER);
        preview.setPadding(0, dp(12), 0, dp(4));
        box.addView(preview);

        final TextView weekHint = new TextView(this);
        weekHint.setTextColor(th.onSurfaceVariant);
        weekHint.setTextSize(11.5f);
        weekHint.setGravity(Gravity.CENTER);
        weekHint.setPadding(0, 0, 0, dp(10));
        box.addView(weekHint);

        box.addView(smallLabel("年"));
        LinearLayout yearRow = new LinearLayout(this);
        yearRow.setOrientation(LinearLayout.HORIZONTAL);
        HorizontalScrollView hy = new HorizontalScrollView(this);
        hy.setHorizontalScrollBarEnabled(false);
        hy.addView(yearRow);
        box.addView(hy);

        box.addView(smallLabel("月"));
        LinearLayout monthRow = new LinearLayout(this);
        monthRow.setOrientation(LinearLayout.HORIZONTAL);
        HorizontalScrollView hm = new HorizontalScrollView(this);
        hm.setHorizontalScrollBarEnabled(false);
        hm.addView(monthRow);
        box.addView(hm);

        box.addView(smallLabel("日"));
        final LinearLayout dayRow = new LinearLayout(this);
        dayRow.setOrientation(LinearLayout.HORIZONTAL);
        HorizontalScrollView hd = new HorizontalScrollView(this);
        hd.setHorizontalScrollBarEnabled(false);
        hd.addView(dayRow);
        box.addView(hd);

        final List<View> yChips = new ArrayList<View>();
        final List<View> mChips = new ArrayList<View>();
        final List<View> dChips = new ArrayList<View>();

        final Runnable sync = new Runnable() {
            public void run() {
                int maxDay = daysInMonth(cur[0], cur[1]);
                if (cur[2] > maxDay) cur[2] = maxDay;
                // 日胶囊按当月天数重建
                dayRow.removeAllViews();
                dChips.clear();
                for (int d = 1; d <= maxDay; d++) {
                    final int dd = d;
                    View c = pickChip(String.valueOf(d), cur[2] == d, new View.OnClickListener() {
                        public void onClick(View v) {
                            cur[2] = dd;
                            rebuildChips(dChips, cur[2]);
                            updateDatePreview(preview, weekHint, cur);
                        }
                    });
                    dChips.add(c);
                    dayRow.addView(c);
                }
                rebuildChips(yChips, cur[0]);
                rebuildChips(mChips, cur[1]);
                rebuildChips(dChips, cur[2]);
                updateDatePreview(preview, weekHint, cur);
            }
        };

        int thisYear = Calendar.getInstance().get(Calendar.YEAR);
        for (int y = thisYear - 2; y <= thisYear + 3; y++) {
            final int yy = y;
            View c = pickChip(String.valueOf(y), cur[0] == y, new View.OnClickListener() {
                public void onClick(View v) {
                    cur[0] = yy;
                    sync.run();
                }
            });
            yChips.add(c);
            yearRow.addView(c);
        }
        for (int m = 1; m <= 12; m++) {
            final int mm = m;
            View c = pickChip(m + "月", cur[1] == m, new View.OnClickListener() {
                public void onClick(View v) {
                    cur[1] = mm;
                    sync.run();
                }
            });
            mChips.add(c);
            monthRow.addView(c);
        }
        sync.run();

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(16);
        row.setLayoutParams(rlp);
        row.addView(cardButton("本周一", false, new View.OnClickListener() {
            public void onClick(View v) {
                Calendar c = Calendar.getInstance();
                int dow = c.get(Calendar.DAY_OF_WEEK);
                c.add(Calendar.DAY_OF_MONTH, dow == Calendar.SUNDAY ? -6 : Calendar.MONDAY - dow);
                cur[0] = c.get(Calendar.YEAR);
                cur[1] = c.get(Calendar.MONTH) + 1;
                cur[2] = c.get(Calendar.DAY_OF_MONTH);
                sync.run();
            }
        }));
        row.addView(cardButton("今天", false, new View.OnClickListener() {
            public void onClick(View v) {
                Calendar c = Calendar.getInstance();
                cur[0] = c.get(Calendar.YEAR);
                cur[1] = c.get(Calendar.MONTH) + 1;
                cur[2] = c.get(Calendar.DAY_OF_MONTH);
                sync.run();
            }
        }));
        View sp = new View(this);
        sp.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));
        row.addView(sp);
        row.addView(cardButton("取消", false, new View.OnClickListener() {
            public void onClick(View v) { closeCard(); }
        }));
        row.addView(cardButton("确定", true, new View.OnClickListener() {
            public void onClick(View v) {
                Calendar c = Calendar.getInstance();
                c.set(cur[0], cur[1] - 1, cur[2]);
                set.startDate = Settings.SDF.format(c.getTime());
                set.save(SettingsActivity.this);
                if (startDateValue != null) {
                    startDateValue.setText(set.startDate + "  " + weekdayCn(set.startDate));
                }
                closeCard(new Runnable() {
                    public void run() {
                        rebuild();
                        Reminder.reschedule(SettingsActivity.this);
                    }
                });
            }
        }));
        box.addView(row);
        Fonts.apply(box);
        return box;
    }

    private int daysInMonth(int y, int m) {
        Calendar c = Calendar.getInstance();
        c.set(y, m - 1, 1);
        return c.getActualMaximum(Calendar.DAY_OF_MONTH);
    }

    private void rebuildChips(List<View> chips, int value) {
        for (View v : chips) {
            if (!(v instanceof TextView)) continue;
            TextView tv = (TextView) v;
            int v0 = Integer.parseInt(tv.getText().toString().replace("月", ""));
            paintChip(tv, v0 == value);
        }
    }

    private void updateDatePreview(TextView preview, TextView hint, int[] cur) {
        Calendar c = Calendar.getInstance();
        c.set(cur[0], cur[1] - 1, cur[2]);
        preview.setText(String.format("%04d-%02d-%02d", cur[0], cur[1], cur[2]));
        String[] wk = {"", "周一", "周二", "周三", "周四", "周五", "周六", "周日"};
        int dow = c.get(Calendar.DAY_OF_WEEK);
        hint.setText(wk[dow == Calendar.SUNDAY ? 7 : dow - 1] + "　（不是周一也没关系，会自动归到那一周的周一）");
    }

    /** 申请通知权限（Android 13+） */
    private void requestNotifPermission() {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            try {
                if (checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                        != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 7301);
                }
            } catch (Throwable ignore) {}
        }
    }

    /** 自定义主题色 */
    private View buildColorContent() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(this);
        title.setText("自定义主题色");
        title.setTextColor(th.primary);
        title.setTextSize(16);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        box.addView(title);

        TextView sub = new TextView(this);
        sub.setText("输入 #RRGGBB，例如 #66CCFF");
        sub.setTextColor(th.onSurfaceVariant);
        sub.setTextSize(11.5f);
        sub.setPadding(0, dp(6), 0, dp(10));
        box.addView(sub);

        final EditText et = new EditText(this);
        et.setText(hex(set.theme));
        et.setSingleLine();
        et.setTextColor(th.onSurface);
        et.setTextSize(16);
        et.setGravity(Gravity.CENTER);
        et.setPadding(dp(14), dp(12), dp(14), dp(12));
        et.setBackground(round(Theme.pct(th.surfaceContainerHigh, 100), crBtn(), 0, 0));
        box.addView(et);

        final TextView preview = new TextView(this);
        preview.setText("");
        preview.setHeight(dp(10));
        box.addView(preview);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(14);
        row.setLayoutParams(rlp);
        row.addView(cardButton("取消", false, new View.OnClickListener() {
            public void onClick(View v) { closeCard(); }
        }));
        View sp = new View(this);
        sp.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));
        row.addView(sp);
        row.addView(cardButton("确定", true, new View.OnClickListener() {
            public void onClick(View v) {
                String hexStr = et.getText().toString().trim();
                if (!hexStr.startsWith("#")) hexStr = "#" + hexStr;
                try {
                    int c = Color.parseColor(hexStr);
                    set.theme = 0xFF000000 | (c & 0xFFFFFF);
                    set.save(SettingsActivity.this);
                    th = Theme.of(set.theme);
                    closeCard(new Runnable() {
                        public void run() { buildShell(); }
                    });
                } catch (Throwable t) {
                    toast("颜色格式不对，试试 #66CCFF");
                }
            }
        }));
        box.addView(row);
        Fonts.apply(box);
        return box;
    }

    /** 清除课表数据 */
    private View buildClearContent() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(this);
        title.setText("清除课表数据？");
        title.setTextColor(th.primary);
        title.setTextSize(16);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        box.addView(title);

        TextView msg = new TextView(this);
        msg.setText("只删除应用内保存的课表，不影响你手机里的 PDF / Excel 文件，也不影响这里的设置。");
        msg.setTextColor(th.onSurfaceVariant);
        msg.setTextSize(12.5f);
        msg.setLineSpacing(0, 1.15f);
        msg.setPadding(0, dp(8), 0, dp(2));
        box.addView(msg);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(16);
        row.setLayoutParams(rlp);
        row.addView(cardButton("取消", false, new View.OnClickListener() {
            public void onClick(View v) { closeCard(); }
        }));
        View sp = new View(this);
        sp.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));
        row.addView(sp);
        row.addView(cardButton("清除", true, new View.OnClickListener() {
            public void onClick(View v) {
                Store.clear(SettingsActivity.this);
                closeCard();
                toast("已清除，回到首页重新导入即可");
            }
        }));
        box.addView(row);
        Fonts.apply(box);
        return box;
    }

    /** 关于：版本 / 作者 / 一句话 + 左下角开源许可、右下角「喵」 */
    private View buildAboutContent() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);

        ImageView mascot = new ImageView(this);
        mascot.setImageResource(R.drawable.ic_mascot);
        mascot.setScaleType(ImageView.ScaleType.CENTER_CROP);
        box.addView(mascot, new LinearLayout.LayoutParams(dp(92), dp(92)));

        TextView ver = new TextView(this);
        ver.setText("版本 7.12");
        ver.setTextColor(th.onSurface);
        ver.setTextSize(16.5f);
        ver.setTypeface(Typeface.DEFAULT_BOLD);
        ver.setGravity(Gravity.CENTER);
        ver.setPadding(0, dp(12), 0, 0);
        box.addView(ver);

        TextView author = new TextView(this);
        author.setText("作者：sanwenTQ＆蓝色大肥鱼");
        author.setTextColor(th.onSurfaceVariant);
        author.setTextSize(13.5f);
        author.setGravity(Gravity.CENTER);
        author.setPadding(0, dp(6), 0, 0);
        box.addView(author);

        TextView slogan = new TextView(this);
        slogan.setText("关注天依喵，关注洛天依谢谢喵");
        slogan.setTextColor(th.primary);
        slogan.setTextSize(14);
        slogan.setTypeface(Typeface.DEFAULT_BOLD);
        slogan.setGravity(Gravity.CENTER);
        slogan.setPadding(0, dp(10), 0, dp(2));
        box.addView(slogan);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(18);
        row.setLayoutParams(rlp);

        final View licBtn = cardButton("开源许可与致谢", false, new View.OnClickListener() {
            public void onClick(View v) { swapContent(buildLicensesContent()); }
        });
        row.addView(licBtn);

        View sp = new View(this);
        sp.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));
        row.addView(sp);

        row.addView(cardButton("喵", true, new View.OnClickListener() {
            public void onClick(View v) { closeCard(); }
        }));
        box.addView(row);

        Fonts.apply(box);
        return box;
    }

    /** 开源许可与致谢 */
    private View buildLicensesContent() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(this);
        title.setText("开源许可与致谢");
        title.setTextColor(th.primary);
        title.setTextSize(16.5f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        box.addView(title);

        ScrollView sv = new ScrollView(this);
        TextView body = new TextView(this);
        body.setText(LICENSES_TEXT);
        body.setTextColor(th.onSurfaceVariant);
        body.setTextSize(11.5f);
        body.setLineSpacing(dp(3), 1.12f);
        body.setPadding(0, dp(10), 0, dp(4));
        sv.addView(body);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        box.addView(sv, slp);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(12);
        row.setLayoutParams(rlp);
        row.addView(cardButton("返回", false, new View.OnClickListener() {
            public void onClick(View v) { swapContent(buildAboutContent()); }
        }));
        View sp = new View(this);
        sp.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));
        row.addView(sp);
        row.addView(cardButton("喵", true, new View.OnClickListener() {
            public void onClick(View v) { closeCard(); }
        }));
        box.addView(row);

        Fonts.apply(box);
        return box;
    }

    private static final String LICENSES_TEXT =
            "TY课程表使用了以下开源项目与资源，在此致谢：\n\n"
            + "【随应用分发的第三方组件】\n"
            + "· PdfBox-Android 2.0.27.0  \n  Tom Roush · Apache-2.0  \n"
            + "  https://github.com/TomRoush/PdfBox-Android\n"
            + "· Apache PDFBox / Apache FontBox 2.0.x  \n"
            + "  Copyright 2002-2021 The Apache Software Foundation · Apache-2.0\n"
            + "· Apache Harmony（java.awt 兼容层）  \n  The Apache Software Foundation · Apache-2.0\n"
            + "· Adobe CMap 资源（assets/com/tom_roush/fontbox/resources/cmap）  \n"
            + "  Copyright 1990-2002 Adobe Systems Incorporated · 随 Apache PDFBox 分发\n"
            + "· Adobe Core 14 字体度量 AFM（assets/com/tom_roush/pdfbox/resources/afm）  \n"
            + "  Copyright Adobe Systems Incorporated · 随 Apache PDFBox 分发\n"
            + "· Liberation Sans Regular（PDFBox 的兜底字体）  \n"
            + "  Copyright 2012 Red Hat, Inc. / DigiTecs Inc. · SIL OFL 1.1\n"
            + "· Unicode 数据文件 Scripts.txt  \n  Copyright 1991-2021 Unicode, Inc. · Unicode License\n"
            + "· HarmonyOS Sans SC Version 1.0（已子集化，见下方「界面字体」）\n\n"
            + "【界面字体 · 请留意单独的使用条款】\n"
            + "· HarmonyOS Sans SC Version 1.0（已做字符子集化）  \n"
            + "  版权：Copyright 2021 Huawei Device Co., Ltd. All Rights Reserved.  \n"
            + "  出品方（name 表）：Huawei Device Co., Ltd & Hanyi Fonts（汉仪）  \n"
            + "  来源：HarmonyOS Sans 官方发布（华为开发者联盟字体页面）  \n"
            + "  说明：字体由华为以「免费商用」方式提供，版权归华为与汉仪所有，\n"
            + "  不适用本应用的 MIT 许可。再分发或商用前请以官方最新条款为准，\n"
            + "  官方页面的许可全文未随本应用一并分发。\n\n"
            + "【仅构建期使用，不随应用分发】\n"
            + "· Android SDK Build-Tools（aapt2 / apksigner / zipalign）· Google · Apache-2.0\n"
            + "· d8 (R8) · Google · BSD-3-Clause\n"
            + "· OpenJDK 17 · GPLv2 with Classpath Exception\n"
            + "· fonttools 4.65 · MIT（生成字体子集）\n"
            + "· xlwt 1.3 · BSD（生成解析回归测试用的 .xls）\n\n"
            + "【本应用自身代码】MIT，见安装包同目录的 LICENSE / assets/licenses/MIT.txt\n\n"
            + "许可全文随安装包分发，位于 assets/licenses/：\n"
            + "Apache-2.0.txt / OFL-1.1.txt / Unicode.txt / MIT.txt / NOTICE.txt";

    private View cardButton(String text, boolean primary, View.OnClickListener l) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(13);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(16), dp(10), dp(16), dp(10));
        if (primary) {
            tv.setTextColor(th.onPrimary);
            tv.setBackground(round(th.primary, crBtn(), 0, 0));
        } else {
            tv.setTextColor(th.onSecondaryContainer);
            tv.setBackground(round(Theme.pct(th.secondaryContainer, 100), crBtn(), 0, 0));
        }
        tv.setOnClickListener(l);
        return tv;
    }

    // ---------------------------------------------------------------- 工具

    private String weekdayCn(String date) {
        try {
            Calendar c = Calendar.getInstance();
            c.setTime(Settings.SDF.parse(date));
            return Settings.weekdayCn(c.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY
                    ? 7 : c.get(Calendar.DAY_OF_WEEK) - 1);
        } catch (Exception e) {
            return "";
        }
    }

    private String hex(int color) { return String.format("#%06X", 0xFFFFFF & color); }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
