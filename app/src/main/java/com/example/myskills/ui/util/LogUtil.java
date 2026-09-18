package com.example.myskills.ui.util;

import android.util.Log;

/**
 * 日志工具类 - 标准版本
 * 支持通过 persist.sys.awell.logswitch 控制日志开关
 * 所有插件统一使用此版本，无需各自维护
 */
public class LogUtil {
    private static String TAG = "Skills";

   // private static int logswitch = Integer.parseInt(SystemProperties.get("persist.sys.awell.logswitch","1"));
    private static boolean isDebuggable = true;

    private LogUtil() {
    }

    /** 设置日志标签，在插件初始化时调用 */
    public static void setTag(String tag) {
        TAG = tag;
    }

    /** 刷新调试开关（从系统属性重新读取） */
    //public static void setIsDebuggable() {
    //    isDebuggable = Integer.parseInt(SystemProperties.get("persist.sys.awell.logswitch", "1")) == 1;
    //}

    /** 兼容旧版 API：刷新日志开关 */
    //public static void setLogSwitch() {
     //   setIsDebuggable();
    //}

    /** 兼容带参数的 setIsDebuggable 调用 */
    //public static void setIsDebuggable(boolean debuggable) {
    //    isDebuggable = debuggable;
    //}

    public static void i(final String message) {
        if (!isDebuggable) return;
        Log.i(TAG, getLogInfo(message));
    }

    public static void d(final String message) {
        if (!isDebuggable) return;
        Log.d(TAG, getLogInfo(message));
    }

    public static void v(final String message) {
        if (!isDebuggable) return;
        Log.v(TAG, getLogInfo(message));
    }

    public static void e(final String message) {
        if (!isDebuggable) return;
        Log.e(TAG, getLogInfo(message));
    }

    public static void e(final String message, Exception ex) {
        if (!isDebuggable) return;
        Log.e(TAG, getLogInfo(message), ex);
    }

    public static void w(final String message) {
        if (!isDebuggable) return;
        Log.w(TAG, getLogInfo(message));
    }

    /** 获取包含调用位置信息的日志文本 */
    private static String getLogInfo(String msg) {
        StackTraceElement[] elements = new Throwable().getStackTrace();
        if (elements.length > 2) {
            StackTraceElement caller = elements[2];
            return "[" + caller.getFileName() + " : " + caller.getMethodName()
                    + " : " + caller.getLineNumber() + "]:" + msg;
        }
        return msg;
    }
}
