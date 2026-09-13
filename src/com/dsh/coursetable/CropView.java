package com.dsh.coursetable;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

/** 手动裁切控件：固定比例的取景框 + 图片可拖动/缩放，保证不出黑边。 */
public class CropView extends View {

    private Bitmap bmp;
    private float aspect = 16f / 9f;

    private final Matrix mat = new Matrix();
    private final Matrix inv = new Matrix();
    private final RectF frame = new RectF();
    private final Paint pBmp = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Paint pDim = new Paint();
    private final Paint pLine = new Paint();
    private final Paint pEdge = new Paint();

    private float minScale = 1f, maxScale = 1f, baseScale = 1f;
    private float lastX, lastY;
    private boolean dragging = false;
    private ScaleGestureDetector scaleDetector;

    public CropView(Context c) { super(c); init(c); }
    public CropView(Context c, AttributeSet a) { super(c, a); init(c); }

    private void init(Context c) {
        pDim.setColor(0xB0000000);
        pLine.setColor(0x66FFFFFF);
        pLine.setStrokeWidth(dp(1));
        pEdge.setColor(0xFFFFFFFF);
        pEdge.setStrokeWidth(dp(2));
        pEdge.setStyle(Paint.Style.STROKE);
        scaleDetector = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector d) {
                if (bmp == null) return true;
                float f = d.getScaleFactor();
                float[] v = new float[9];
                mat.getValues(v);
                float cur = v[Matrix.MSCALE_X];
                float next = clamp(cur * f, minScale, maxScale);
                float k = next / cur;
                mat.postScale(k, k, d.getFocusX(), d.getFocusY());
                clampMatrix();
                invalidate();
                return true;
            }
        });
    }

    public void setBitmap(Bitmap b, float targetAspect) {
        this.bmp = b;
        if (targetAspect > 0.1f) this.aspect = targetAspect;
        requestLayout();
        post(new Runnable() {
            public void run() { resetImage(); }
        });
    }

    public RectF frameRect() { return frame; }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        float pad = dp(16);
        float availW = w - pad * 2, availH = h - pad * 2;
        float fw = availW, fh = fw / aspect;
        if (fh > availH) { fh = availH; fw = fh * aspect; }
        float left = (w - fw) / 2f, top = (h - fh) / 2f;
        frame.set(left, top, left + fw, top + fh);
        resetImage();
    }

    private void resetImage() {
        if (bmp == null || frame.width() <= 0) return;
        float s = Math.max(frame.width() / bmp.getWidth(), frame.height() / bmp.getHeight());
        baseScale = s;
        minScale = s;
        maxScale = s * 5f;
        mat.reset();
        mat.postScale(s, s);
        mat.postTranslate(frame.centerX() - bmp.getWidth() * s / 2f,
                frame.centerY() - bmp.getHeight() * s / 2f);
        clampMatrix();
        invalidate();
    }

    /** 保证图片始终盖住取景框 */
    private void clampMatrix() {
        if (bmp == null) return;
        float[] v = new float[9];
        mat.getValues(v);
        float s = v[Matrix.MSCALE_X];
        float iw = bmp.getWidth() * s, ih = bmp.getHeight() * s;
        float left = v[Matrix.MTRANS_X], top = v[Matrix.MTRANS_Y];
        float dx = 0, dy = 0;
        if (left > frame.left) dx = frame.left - left;
        if (left + iw < frame.right) dx = frame.right - (left + iw);
        if (top > frame.top) dy = frame.top - top;
        if (top + ih < frame.bottom) dy = frame.bottom - (top + ih);
        if (dx != 0 || dy != 0) mat.postTranslate(dx, dy);
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        if (bmp == null) return;
        c.drawBitmap(bmp, mat, pBmp);
        // 取景框外的遮罩
        c.drawRect(0, 0, getWidth(), frame.top, pDim);
        c.drawRect(0, frame.bottom, getWidth(), getHeight(), pDim);
        c.drawRect(0, frame.top, frame.left, frame.bottom, pDim);
        c.drawRect(frame.right, frame.top, getWidth(), frame.bottom, pDim);
        // 三分线 + 边框
        float w = frame.width() / 3f, h = frame.height() / 3f;
        for (int i = 1; i <= 2; i++) {
            c.drawLine(frame.left + w * i, frame.top, frame.left + w * i, frame.bottom, pLine);
            c.drawLine(frame.left, frame.top + h * i, frame.right, frame.top + h * i, pLine);
        }
        c.drawRect(frame, pEdge);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (bmp == null) return false;
        scaleDetector.onTouchEvent(e);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastX = e.getX(); lastY = e.getY(); dragging = true;
                getParent().requestDisallowInterceptTouchEvent(true);
                break;
            case MotionEvent.ACTION_MOVE:
                if (dragging && !scaleDetector.isInProgress()) {
                    mat.postTranslate(e.getX() - lastX, e.getY() - lastY);
                    lastX = e.getX(); lastY = e.getY();
                    clampMatrix();
                    invalidate();
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                break;
        }
        return true;
    }

    /** 按取景框裁出图片（输出长边不超过 maxOut） */
    public Bitmap crop(int maxOut) {
        if (bmp == null) return null;
        try {
            mat.invert(inv);
            RectF src = new RectF(frame);
            inv.mapRect(src);
            int x = Math.max(0, Math.round(src.left));
            int y = Math.max(0, Math.round(src.top));
            int w = Math.min(bmp.getWidth() - x, Math.round(src.width()));
            int h = Math.min(bmp.getHeight() - y, Math.round(src.height()));
            if (w <= 0 || h <= 0) return null;
            Bitmap out = Bitmap.createBitmap(bmp, x, y, w, h);
            int longSide = Math.max(w, h);
            if (maxOut > 0 && longSide > maxOut) {
                float k = (float) maxOut / longSide;
                Bitmap scaled = Bitmap.createScaledBitmap(out, Math.round(w * k), Math.round(h * k), true);
                if (scaled != out) out.recycle();
                out = scaled;
            }
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    private float clamp(float v, float lo, float hi) { return v < lo ? lo : (v > hi ? hi : v); }

    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }
}
