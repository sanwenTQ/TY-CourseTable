package com.dsh.coursetable;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** 应用内统一的自绘圆角弹窗（不使用系统原生样式）。 */
public class Sheet {

    public interface OnAction { void run(); }
    public interface OnInput { void run(String text); }
    public interface OnPick { void pick(int index); }

    private final Activity a;
    private final Theme th;
    private final int corner;

    public Sheet(Activity a, Theme th, int cornerDp) {
        this.a = a;
        this.th = th;
        this.corner = cornerDp;
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, a.getResources().getDisplayMetrics());
    }

    public GradientDrawable round(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    /** 通用弹窗：标题 + 内容 + 底部按钮（第一个为主按钮） */
    public Dialog build(String title, CharSequence message, View content,
                        String[] labels, final OnAction[] actions, boolean cancelable) {
        final Dialog d = new Dialog(a);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout card = new LinearLayout(a);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(round(th.surface, corner));
        int p = dp(20);
        card.setPadding(p, dp(16), p, dp(12));

        if (title != null && title.length() > 0) {
            TextView t = new TextView(a);
            t.setText(title);
            t.setTextColor(th.primary);
            t.setTextSize(17);
            t.setTypeface(Typeface.DEFAULT_BOLD);
            card.addView(t);
        }
        if (message != null && message.length() > 0) {
            TextView m = new TextView(a);
            m.setText(message);
            m.setTextColor(th.onSurfaceVariant);
            m.setTextSize(13);
            m.setLineSpacing(0, 1.18f);
            m.setPadding(0, dp(8), 0, 0);
            card.addView(m);
        }
        if (content != null) {
            if (content.getParent() instanceof ViewGroup) {
                ((ViewGroup) content.getParent()).removeView(content);
            }
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.topMargin = dp(10);
            card.addView(content, clp);
        }
        if (labels != null && labels.length > 0) {
            LinearLayout row = new LinearLayout(a);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.END);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.topMargin = dp(14);
            card.addView(row, rlp);
            for (int i = labels.length - 1; i >= 0; i--) {
                final int idx = i;
                boolean primary = (i == labels.length - 1);
                TextView b = new TextView(a);
                b.setText(labels[i]);
                b.setTextSize(14);
                b.setTypeface(Typeface.DEFAULT_BOLD);
                b.setGravity(Gravity.CENTER);
                b.setPadding(dp(18), dp(10), dp(18), dp(10));
                if (primary) {
                    b.setTextColor(th.onPrimary);
                    b.setBackground(round(th.primary, corner));
                } else {
                    b.setTextColor(th.primary);
                    b.setBackground(round(Theme.pct(th.primary, 24), corner));
                }
                b.setOnClickListener(new View.OnClickListener() {
                    public void onClick(View v) {
                        d.dismiss();
                        if (actions != null && idx < actions.length && actions[idx] != null) actions[idx].run();
                    }
                });
                LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                blp.leftMargin = dp(8);
                row.addView(b, blp);
            }
        }

        ScrollView sv = new ScrollView(a);
        sv.addView(card);
        d.setContentView(sv);

        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.dimAmount = 0.5f;
            w.setAttributes(lp);
            w.setLayout(dp(300), ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        d.setCancelable(cancelable);
        d.setCanceledOnTouchOutside(cancelable);
        return d;
    }

    public Dialog message(String title, CharSequence msg) {
        return build(title, msg, null, new String[]{"知道了"}, null, true);
    }

    public Dialog confirm(String title, CharSequence msg, String okLabel, String cancelLabel, final OnAction ok) {
        return build(title, msg, null, new String[]{cancelLabel, okLabel},
                new OnAction[]{null, ok}, true);
    }

    private EditText lastEdit;

    /** 供自定义弹窗复用的输入框（圆角） */
    public EditText inputBox(String value, String hint, boolean number) {
        EditText et = new EditText(a);
        et.setText(value == null ? "" : value);
        et.setHint(hint == null ? "" : hint);
        et.setSingleLine();
        et.setTextColor(th.onSurface);
        et.setHintTextColor(Theme.pct(th.onSurfaceVariant, 150));
        et.setTextSize(15);
        et.setPadding(dp(14), dp(10), dp(14), dp(10));
        et.setBackground(round(Theme.pct(th.surfaceContainerHigh, 100), Math.max(6, corner - 4)));
        et.setImeOptions(EditorInfo.IME_ACTION_DONE);
        et.setInputType(number ? InputType.TYPE_CLASS_NUMBER : InputType.TYPE_CLASS_TEXT);
        lastEdit = et;
        return et;
    }

    public String lastInput() { return lastEdit == null ? "" : lastEdit.getText().toString(); }

    public Dialog input(String title, String hint, String value, boolean number, final OnInput ok) {
        EditText et = new EditText(a);
        et.setText(value == null ? "" : value);
        et.setHint(hint == null ? "" : hint);
        et.setSingleLine();
        et.setTextColor(th.onSurface);
        et.setHintTextColor(Theme.pct(th.onSurfaceVariant, 150));
        et.setTextSize(15);
        et.setPadding(dp(14), dp(10), dp(14), dp(10));
        et.setBackground(round(Theme.pct(th.surfaceContainerHigh, 100), Math.max(6, corner - 4)));
        et.setImeOptions(EditorInfo.IME_ACTION_DONE);
        et.setInputType(number ? InputType.TYPE_CLASS_NUMBER : InputType.TYPE_CLASS_TEXT);
        final EditText ref = et;
        return build(title, null, et, new String[]{"取消", "确定"},
                new OnAction[]{null, new OnAction() {
                    public void run() { if (ok != null) ok.run(ref.getText().toString()); }
                }}, true);
    }

    public Dialog list(String title, final String[] items, final OnPick pick) {
        final LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        final Dialog dlg = build(title, null, box, new String[]{"关闭"}, null, true);
        for (int i = 0; i < items.length; i++) {
            final int idx = i;
            TextView tv = new TextView(a);
            tv.setText(items[i]);
            tv.setTextColor(th.onSurface);
            tv.setTextSize(14.5f);
            tv.setPadding(dp(14), dp(12), dp(14), dp(12));
            tv.setBackground(round(Theme.pct(th.surfaceContainerHigh, 100), Math.max(6, corner - 4)));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(8);
            tv.setLayoutParams(lp);
            tv.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    dlg.dismiss();
                    if (pick != null) pick.pick(idx);
                }
            });
            box.addView(tv);
        }
        return dlg;
    }

    /** 关于：版本号 / 作者 / 一句话（应用内「关于」页在 SettingsActivity 的自绘卡片里） */
    public Dialog about(final OnAction onOk) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(0, dp(6), 0, 0);

        android.widget.ImageView mascot = new android.widget.ImageView(a);
        mascot.setImageResource(R.drawable.ic_mascot);
        mascot.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(dp(84), dp(84));
        box.addView(mascot, mlp);

        TextView ver = new TextView(a);
        ver.setText("版本 7.12");
        ver.setTextColor(th.onSurface);
        ver.setTextSize(16);
        ver.setTypeface(Typeface.DEFAULT_BOLD);
        ver.setGravity(Gravity.CENTER);
        ver.setPadding(0, dp(12), 0, 0);
        box.addView(ver);

        TextView author = new TextView(a);
        author.setText("作者：sanwenTQ＆蓝色大肥鱼");
        author.setTextColor(th.onSurfaceVariant);
        author.setTextSize(13.5f);
        author.setGravity(Gravity.CENTER);
        author.setPadding(0, dp(6), 0, 0);
        box.addView(author);

        TextView slogan = new TextView(a);
        slogan.setText("关注天依喵，关注洛天依谢谢喵");
        slogan.setTextColor(th.primary);
        slogan.setTextSize(14);
        slogan.setTypeface(Typeface.DEFAULT_BOLD);
        slogan.setGravity(Gravity.CENTER);
        slogan.setPadding(0, dp(10), 0, dp(4));
        box.addView(slogan);

        return build("关于 TY课程表", null, box, new String[]{"喵"}, null, true);
    }
}
