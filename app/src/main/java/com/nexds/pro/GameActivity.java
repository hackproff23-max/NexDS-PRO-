package com.nexds.pro;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.io.File;
import java.util.concurrent.locks.LockSupport;

public class GameActivity extends Activity implements SurfaceHolder.Callback, ControlsView.Listener {
    private static final int C_SAVE = 1, C_LOAD = 2, C_RESET = 3, C_SRAM = 4;

    private SurfaceView sv;
    private ControlsView cv;
    private Thread emu;
    private volatile boolean running = true, paused = false, ff = false, menuOpen = false;
    private volatile int cmd = 0;
    private int touchMask, keyMask, axisMask;
    private String romPath, srm, state;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        romPath = getIntent().getStringExtra("rom");
        if (romPath == null) { finish(); return; }
        if (Build.VERSION.SDK_INT >= 28)
            getWindow().getAttributes().layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;

        String base = new File(romPath).getName();
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);
        File saves = new File(getFilesDir(), "saves");
        saves.mkdirs();
        srm = new File(saves, base + ".srm").getPath();
        state = new File(saves, base + ".state").getPath();

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        sv = new SurfaceView(this);
        sv.getHolder().addCallback(this);
        sv.setKeepScreenOn(true);
        root.addView(sv, new FrameLayout.LayoutParams(1, 1));
        cv = new ControlsView(this);
        cv.setListener(this);
        root.addView(cv, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        hideUi();

        emu = new Thread(this::emuLoop, "emu");
        emu.start();
    }

    private void hideUi() {
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    }

    @Override public void onWindowFocusChanged(boolean f) { super.onWindowFocusChanged(f); if (f) hideUi(); }

    private void toast(final String s) { runOnUiThread(() -> Toast.makeText(this, s, Toast.LENGTH_SHORT).show()); }

    private void fail(final String s) {
        runOnUiThread(() -> { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); finish(); });
    }

    private AudioTrack buildTrack(int rate) {
        try {
            int ch = AudioFormat.CHANNEL_OUT_STEREO, enc = AudioFormat.ENCODING_PCM_16BIT;
            int min = AudioTrack.getMinBufferSize(rate, ch, enc);
            int buf = Math.max(min, rate / 10 * 4);
            AudioTrack t = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(new AudioFormat.Builder()
                    .setSampleRate(rate).setChannelMask(ch).setEncoding(enc).build())
                .setBufferSizeInBytes(buf)
                .setTransferMode(AudioTrack.MODE_STREAM).build();
            t.play();
            return t;
        } catch (Throwable t) {
            return null;
        }
    }

    private void emuLoop() {
        AudioTrack track = null;
        boolean inited = false;
        try {
            String core = getApplicationInfo().nativeLibraryDir + "/libretro_core.so";
            if (!new File(core).exists()) { fail("Core melonDS nao encontrado no APK"); return; }
            File sys = new File(getFilesDir(), "system");
            sys.mkdirs();
            inited = Native.nativeInit(core, sys.getPath(), new File(getFilesDir(), "saves").getPath());
            if (!inited) { fail("Falha ao iniciar o core"); return; }
            if (!Native.nativeLoadGame(romPath)) { fail("Falha ao carregar a ROM"); return; }
            Native.nativeLoadSram(srm);

            double fps = Native.nativeFps();
            if (fps < 1) fps = 60;
            int rate = (int) Native.nativeSampleRate();
            if (rate < 8000) rate = 32768;
            int w = Native.nativeWidth(), h = Native.nativeHeight();
            final float asp = (w > 0 && h > 0) ? (float) w / h : 256f / 384f;
            runOnUiThread(() -> cv.setAspect(asp));

            track = buildTrack(rate);
            boolean audioOk = track != null;
            short[] abuf = new short[8192];
            long frameNs = (long) (1e9 / fps);
            long next = System.nanoTime(), lastSave = next;

            while (running) {
                int c = cmd;
                if (c != 0) { cmd = 0; doCmd(c); }
                if (paused) { LockSupport.parkNanos(30_000_000L); next = System.nanoTime(); continue; }

                Native.nativeRun();
                boolean fast = ff;
                int n;
                while ((n = Native.nativeAudio(abuf)) > 0) {
                    if (!fast && audioOk) track.write(abuf, 0, n);
                }

                long now = System.nanoTime();
                if (fast || !audioOk) {
                    next += fast ? frameNs / 4 : frameNs;
                    long d = next - now;
                    if (d > 0) LockSupport.parkNanos(d);
                    else if (d < -100_000_000L) next = now;
                } else {
                    next = now;
                }
                if (now - lastSave > 15_000_000_000L) { Native.nativeSaveSram(srm); lastSave = now; }
            }
        } catch (Throwable t) {
            fail("Erro: " + t);
        } finally {
            if (inited) {
                try { Native.nativeSaveSram(srm); } catch (Throwable ignored) {}
                try { Native.nativeUnload(); } catch (Throwable ignored) {}
            }
            if (track != null) { try { track.release(); } catch (Throwable ignored) {} }
        }
    }

    private void doCmd(int c) {
        switch (c) {
            case C_SAVE: toast(Native.nativeSaveState(state) ? "Estado salvo" : "Falha ao salvar estado"); break;
            case C_LOAD: toast(Native.nativeLoadState(state) ? "Estado carregado" : "Nenhum estado salvo"); break;
            case C_RESET: Native.nativeReset(); break;
            case C_SRAM: Native.nativeSaveSram(srm); break;
        }
    }

    // ---- Surface ----
    @Override public void surfaceCreated(SurfaceHolder h) { Native.nativeSetSurface(h.getSurface()); }
    @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int hh) {}
    @Override public void surfaceDestroyed(SurfaceHolder h) { Native.nativeSetSurface(null); }

    // ---- ControlsView.Listener ----
    @Override public void onGameRect(int l, int t, int w, int h) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h, Gravity.TOP | Gravity.START);
        lp.leftMargin = l; lp.topMargin = t;
        sv.setLayoutParams(lp);
    }
    @Override public void onMask(int m) { touchMask = m; apply(); }
    @Override public void onTouch(int x, int y, boolean p) { Native.nativeSetTouch(x, y, p); }
    @Override public void onMenu() { showMenu(); }
    @Override public void onFast() { toggleFast(); }

    private void apply() { Native.nativeSetButtons(touchMask | keyMask | axisMask); }

    private void toggleFast() {
        ff = !ff;
        cv.setFast(ff);
        toast(ff ? "Acelerar: ligado" : "Acelerar: desligado");
    }

    private void showMenu() {
        if (menuOpen) return;
        menuOpen = true; paused = true;
        String[] items = {"Continuar", ff ? "Acelerar: LIGADO" : "Acelerar: desligado",
            "Salvar estado", "Carregar estado", "Reiniciar", "Sair"};
        new AlertDialog.Builder(this).setTitle("NexDS PRO").setItems(items, (d, which) -> {
            switch (which) {
                case 1: toggleFast(); break;
                case 2: cmd = C_SAVE; break;
                case 3: cmd = C_LOAD; break;
                case 4: cmd = C_RESET; break;
                case 5: finish(); break;
            }
        }).setOnDismissListener(d -> { menuOpen = false; paused = false; }).show();
    }

    @Override public void onBackPressed() { showMenu(); }

    // ---- Controle fisico (Bluetooth/USB) ----
    private static int mapKey(int k) {
        switch (k) {
            case KeyEvent.KEYCODE_DPAD_UP: return 4;
            case KeyEvent.KEYCODE_DPAD_DOWN: return 5;
            case KeyEvent.KEYCODE_DPAD_LEFT: return 6;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return 7;
            case KeyEvent.KEYCODE_BUTTON_A: return 0;   // B do DS (posicao fisica)
            case KeyEvent.KEYCODE_BUTTON_B: return 8;   // A do DS
            case KeyEvent.KEYCODE_BUTTON_X: return 1;   // Y do DS
            case KeyEvent.KEYCODE_BUTTON_Y: return 9;   // X do DS
            case KeyEvent.KEYCODE_BUTTON_L1: case KeyEvent.KEYCODE_BUTTON_L2: return 10;
            case KeyEvent.KEYCODE_BUTTON_R1: case KeyEvent.KEYCODE_BUTTON_R2: return 11;
            case KeyEvent.KEYCODE_BUTTON_START: return 3;
            case KeyEvent.KEYCODE_BUTTON_SELECT: return 2;
            default: return -1;
        }
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        int bit = mapKey(e.getKeyCode());
        if (bit >= 0) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) keyMask |= 1 << bit;
            else if (e.getAction() == KeyEvent.ACTION_UP) keyMask &= ~(1 << bit);
            apply();
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    @Override public boolean onGenericMotionEvent(MotionEvent e) {
        if ((e.getSource() & InputDevice.SOURCE_JOYSTICK) != 0 && e.getAction() == MotionEvent.ACTION_MOVE) {
            float x = e.getAxisValue(MotionEvent.AXIS_HAT_X), y = e.getAxisValue(MotionEvent.AXIS_HAT_Y);
            float sx = e.getAxisValue(MotionEvent.AXIS_X), sy = e.getAxisValue(MotionEvent.AXIS_Y);
            int m = 0;
            if (y < -0.5f || sy < -0.5f) m |= 1 << 4;
            if (y > 0.5f || sy > 0.5f) m |= 1 << 5;
            if (x < -0.5f || sx < -0.5f) m |= 1 << 6;
            if (x > 0.5f || sx > 0.5f) m |= 1 << 7;
            axisMask = m;
            apply();
            return true;
        }
        return super.onGenericMotionEvent(e);
    }

    // ---- Ciclo de vida ----
    @Override protected void onPause() {
        super.onPause();
        paused = true;
        cmd = C_SRAM;
    }

    @Override protected void onResume() {
        super.onResume();
        paused = menuOpen;
    }

    @Override protected void onDestroy() {
        running = false;
        if (emu != null) { try { emu.join(4000); } catch (InterruptedException ignored) {} }
        super.onDestroy();
    }
}
