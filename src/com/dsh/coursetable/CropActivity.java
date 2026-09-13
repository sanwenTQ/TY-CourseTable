package com.dsh.coursetable;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

/** 背景图手动裁切页：按当前屏幕比例取景，用户自己拖/缩，不帮用户压缩变形。 */
public class CropActivity extends Activity {

    public static final String EXTRA_LANDSCAPE = "landscape";

    private CropView cropView;
    private boolean landscape;
    private Theme th;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Fonts.init(getApplicationContext());
        th = Theme.of(Settings.load(this).theme);
        landscape = getIntent() != null && getIntent().getBooleanExtra(EXTRA_LANDSCAPE, true);

        if (!Bg.hasSrc(this)) {
            Toast.makeText(this, "还没有选择图片", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        Bitmap src = Bg.loadSrc(this, 2048);
        if (src == null) {
            Toast.makeText(this, "图片读取失败", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        DisplayMetrics dm = getResources().getDisplayMetrics();
        float aspect = landscape ? Math.max(dm.widthPixels, dm.heightPixels) / (float) Math.min(dm.widthPixels, dm.heightPixels)
                : Math.min(dm.widthPixels, dm.heightPixels) / (float) Math.max(dm.widthPixels, dm.heightPixels);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF101418);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(10), dp(10), dp(10), dp(10));

        TextView cancel = barButton("取消", false, new View.OnClickListener() {
            public void onClick(View v) { finish(); }
        });
        bar.addView(cancel);

        TextView title = new TextView(this);
        title.setText((landscape ? "裁切横屏背景" : "裁切竖屏背景") + "　按屏幕比例取景");
        title.setTextColor(Color.WHITE);
        title.setTextSize(14);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        title.setGravity(Gravity.CENTER);
        bar.addView(title);

        TextView ok = barButton("完成", true, new View.OnClickListener() {
            public void onClick(View v) { doCrop(); }
        });
        bar.addView(ok);
        root.addView(bar);

        FrameLayout holder = new FrameLayout(this);
        cropView = new CropView(this);
        holder.addView(cropView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(holder, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView hint = new TextView(this);
        hint.setText("拖动移动位置 · 双指缩放 · 框内即最终显示区域");
        hint.setTextColor(0xCCFFFFFF);
        hint.setTextSize(12);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, dp(6), 0, dp(12));
        root.addView(hint);

        setContentView(root);
        Fonts.apply(root);
        cropView.setBitmap(src, aspect);
    }

    private TextView barButton(String text, boolean primary, View.OnClickListener l) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(14);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dp(16), dp(9), dp(16), dp(9));
        if (primary) {
            tv.setTextColor(Color.WHITE);
            tv.setBackgroundColor(th.primary);
        } else {
            tv.setTextColor(0xCCFFFFFF);
        }
        tv.setOnClickListener(l);
        return tv;
    }

    private void doCrop() {
        Bitmap out = cropView.crop(2000);
        if (out == null) {
            Toast.makeText(this, "裁切失败，请重试", Toast.LENGTH_SHORT).show();
            return;
        }
        Bg.saveCrop(this, landscape, out);
        Settings s = Settings.load(this);
        s.bg = Bg.cropFile(this, landscape).getName();
        s.save(this);
        Toast.makeText(this, "已保存" + (landscape ? "横屏" : "竖屏") + "背景", Toast.LENGTH_SHORT).show();
        finish();
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }
}
