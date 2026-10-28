package com.nexds.pro;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/** Controles virtuais + area de toque da tela inferior do DS. Ids de bit = joypad libretro. */
public class ControlsView extends View {
    public interface Listener {
        void onMask(int mask);
        void onTouch(int x, int y, boolean pressed);
        void onMenu();
        void onFast();
        void onGameRect(int l, int t, int w, int h);
    }

    private static final int BIT_MENU = -1, BIT_FF = -2;

    private static class Btn {
        int bit; String label; float cx, cy, w, h; boolean round;
        boolean hit(float x, float y) {
            if (round) { float dx = x - cx, dy = y - cy, r = w * 1.25f; return dx * dx + dy * dy <= r * r; }
            return Math.abs(x - cx) <= w * 0.55f && Math.abs(y - cy) <= h * 0.7f;
        }
    }

    private final List<Btn> btns = new ArrayList<>();
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path tri = new Path();
    private float gx, gy, gw, gh, aspect = 256f / 384f;
    private float dcx, dcy, dr;
    private Listener l;
    private int curMask, lastTx, lastTy;
    private boolean fast, lastTouch;

    public ControlsView(Context c) {
        super(c);
        float den = c.getResources().getDisplayMetrics().density;
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(2f * den);
        line.setColor(0xB0FFFFFF);
        fill.setStyle(Paint.Style.FILL);
        text.setColor(0xE0FFFFFF);
        text.setTextAlign(Paint.Align.CENTER);
        text.setFakeBoldText(true);
        tri.moveTo(0, -1f); tri.lineTo(0.8f, 0.6f); tri.lineTo(-0.8f, 0.6f); tri.close();
    }

    public void setListener(Listener li) { l = li; notifyRect(); }
    public void setAspect(float a) { if (a > 0.1f) { aspect = a; compute(); invalidate(); } }
    public void setFast(boolean f) { fast = f; invalidate(); }

    private void notifyRect() { if (l != null && gw > 0) l.onGameRect((int) gx, (int) gy, (int) gw, (int) gh); }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) { compute(); }

    private Btn add(int bit, String label, float cx, float cy, float w, float h, boolean round) {
        Btn b = new Btn(); b.bit = bit; b.label = label; b.cx = cx; b.cy = cy; b.w = w; b.h = h; b.round = round;
        btns.add(b);
        return b;
    }

    private void compute() {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        btns.clear();
        boolean land = w > h;
        if (land) {
            gh = h; gw = gh * aspect;
            if (gw > w * 0.62f) { gw = w * 0.62f; gh = gw / aspect; }
        } else {
            gw = w; gh = gw / aspect;
            if (gh > h * 0.68f) { gh = h * 0.68f; gw = gh * aspect; }
        }
        gx = (w - gw) / 2f; gy = 0;

        float lcx, rcx, cy, r;
        if (land) {
            lcx = gx / 2f; rcx = w - gx / 2f; cy = h * 0.6f;
            r = Math.min(gx * 0.46f, h * 0.22f);
            float bw = r * 1.5f, bh = r * 0.5f, sw = r * 1.1f, sh = r * 0.42f;
            add(10, "L", lcx, h * 0.1f, bw, bh, false);
            add(11, "R", rcx, h * 0.1f, bw, bh, false);
            add(BIT_MENU, "MENU", lcx, h * 0.3f, sw, sh, false);
            add(BIT_FF, "FF", rcx, h * 0.3f, sw, sh, false);
            add(2, "SELECT", lcx, h * 0.9f, sw, sh, false);
            add(3, "START", rcx, h * 0.9f, sw, sh, false);
        } else {
            float top = gy + gh, ch = h - top;
            lcx = w * 0.24f; rcx = w * 0.76f; cy = top + ch * 0.5f;
            r = Math.min(w * 0.2f, ch * 0.28f);
            float bw = r * 1.5f, bh = r * 0.5f, sw = w * 0.2f, sh = r * 0.4f;
            add(10, "L", lcx, top + ch * 0.1f, bw, bh, false);
            add(11, "R", rcx, top + ch * 0.1f, bw, bh, false);
            float by = top + ch * 0.9f;
            add(2, "SELECT", w * 0.15f, by, sw, sh, false);
            add(BIT_FF, "FF", w * 0.38f, by, sw, sh, false);
            add(BIT_MENU, "MENU", w * 0.62f, by, sw, sh, false);
            add(3, "START", w * 0.85f, by, sw, sh, false);
        }
        dcx = lcx; dcy = cy; dr = r;
        float d = r * 0.62f, br = r * 0.36f;
        add(8, "A", rcx + d, cy, br, br, true);
        add(0, "B", rcx, cy + d, br, br, true);
        add(9, "X", rcx, cy - d, br, br, true);
        add(1, "Y", rcx - d, cy, br, br, true);
        notifyRect();
    }

    private boolean on(Btn b) {
        if (b.bit == BIT_FF) return fast;
        return b.bit >= 0 && ((curMask >> b.bit) & 1) != 0;
    }

    @Override protected void onDraw(Canvas c) {
        for (Btn b : btns) {
            boolean p = on(b);
            fill.setColor(p ? 0x90FFFFFF : 0x22FFFFFF);
            if (b.round) {
                c.drawCircle(b.cx, b.cy, b.w, fill);
                c.drawCircle(b.cx, b.cy, b.w, line);
                text.setTextSize(b.w * 1.0f);
                c.drawText(b.label, b.cx, b.cy + b.w * 0.35f, text);
            } else {
                RectF rc = new RectF(b.cx - b.w / 2, b.cy - b.h / 2, b.cx + b.w / 2, b.cy + b.h / 2);
                c.drawRoundRect(rc, b.h / 2, b.h / 2, fill);
                c.drawRoundRect(rc, b.h / 2, b.h / 2, line);
                text.setTextSize(b.h * 0.5f);
                c.drawText(b.label, b.cx, b.cy + b.h * 0.18f, text);
            }
        }
        // D-pad
        fill.setColor(0x22FFFFFF);
        c.drawCircle(dcx, dcy, dr, fill);
        c.drawCircle(dcx, dcy, dr, line);
        int[] bits = {4, 7, 5, 6};
        float[] ang = {0, 90, 180, 270};
        float[][] off = {{0, -1}, {1, 0}, {0, 1}, {-1, 0}};
        for (int i = 0; i < 4; i++) {
            float px = dcx + off[i][0] * dr * 0.62f, py = dcy + off[i][1] * dr * 0.62f;
            boolean p = ((curMask >> bits[i]) & 1) != 0;
            if (p) { fill.setColor(0x90FFFFFF); c.drawCircle(px, py, dr * 0.3f, fill); }
            c.save();
            c.translate(px, py);
            c.rotate(ang[i]);
            c.scale(dr * 0.16f, dr * 0.16f);
            fill.setColor(0xCCFFFFFF);
            c.drawPath(tri, fill);
            c.restore();
        }
    }

    private int hitMask(float x, float y) {
        int m = 0;
        float dx = x - dcx, dy = y - dcy, dist = (float) Math.hypot(dx, dy);
        if (dist <= dr * 1.35f && dist > dr * 0.18f) {
            float nx = dx / dist, ny = dy / dist;
            if (ny < -0.38f) m |= 1 << 4;
            if (ny > 0.38f) m |= 1 << 5;
            if (nx < -0.38f) m |= 1 << 6;
            if (nx > 0.38f) m |= 1 << 7;
        }
        for (Btn b : btns) if (b.bit >= 0 && b.hit(x, y)) m |= 1 << b.bit;
        return m;
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        int a = e.getActionMasked();
        int skip = (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_POINTER_UP) ? e.getActionIndex() : -1;
        boolean cancel = a == MotionEvent.ACTION_CANCEL;

        if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_POINTER_DOWN) {
            int i = e.getActionIndex();
            for (Btn b : btns) {
                if (b.bit < 0 && b.hit(e.getX(i), e.getY(i))) {
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                    if (l != null) { if (b.bit == BIT_MENU) l.onMenu(); else l.onFast(); }
                }
            }
        }

        int mask = 0;
        boolean tp = false;
        int tx = lastTx, ty = lastTy;
        if (!cancel) {
            for (int i = 0; i < e.getPointerCount(); i++) {
                if (i == skip) continue;
                float x = e.getX(i), y = e.getY(i);
                if (x >= gx && x <= gx + gw && y >= gy && y <= gy + gh) {
                    if (!tp) {
                        tp = true;
                        tx = clamp(((x - gx) / gw * 2f - 1f) * 32767f);
                        ty = clamp(((y - gy) / gh * 2f - 1f) * 32767f);
                    }
                } else {
                    mask |= hitMask(x, y);
                }
            }
        }
        if ((mask & ~curMask) != 0) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        if (mask != curMask) { curMask = mask; if (l != null) l.onMask(mask); invalidate(); }
        if (l != null && (tp != lastTouch || (tp && (tx != lastTx || ty != lastTy)))) l.onTouch(tx, ty, tp);
        lastTouch = tp; lastTx = tx; lastTy = ty;
        return true;
    }

    private static int clamp(float v) { return (int) Math.max(-32767f, Math.min(32767f, v)); }
}
