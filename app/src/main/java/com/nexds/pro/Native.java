package com.nexds.pro;

import android.view.Surface;

public final class Native {
    static { System.loadLibrary("nexds"); }

    public static native boolean nativeInit(String core, String sysDir, String saveDir);
    public static native boolean nativeLoadGame(String path);
    public static native void nativeRun();
    public static native int nativeAudio(short[] out);
    public static native double nativeFps();
    public static native double nativeSampleRate();
    public static native int nativeWidth();
    public static native int nativeHeight();
    public static native void nativeSetSurface(Surface s);
    public static native void nativeSetButtons(int mask);
    public static native void nativeSetTouch(int x, int y, boolean pressed);
    public static native boolean nativeSaveState(String path);
    public static native boolean nativeLoadState(String path);
    public static native boolean nativeSaveSram(String path);
    public static native boolean nativeLoadSram(String path);
    public static native void nativeReset();
    public static native void nativeUnload();
    public static native String nativeGetLog();

    private Native() {}
}
