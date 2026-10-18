package com.ryu.sprdflasher;

import android.content.Context;
import java.io.File;

/** ADB/Fastboot 已移除，保留空实现避免旧调用崩溃 */
public class NativeBinHelper {
    public static void ensureExtracted(Context ctx) { /* no-op */ }
    public static File getAdb(Context ctx) { return null; }
    public static File getFastboot(Context ctx) { return null; }
}
