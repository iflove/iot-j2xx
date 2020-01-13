package com.ftdi.j2xx.d2xx;

import android.util.Log;

/**
 * Created by rentianlong on 2020-01-13
 */
public class D2xxLog {

    private static HookLog mHookLog = new HookLog() {
        @Override
        public void verbose(String tag, String text) {
            Log.v(tag, text);
        }

        @Override
        public void debug(String tag, String text) {
            Log.d(tag, text);
        }

        @Override
        public void info(String tag, String text) {
            Log.i(tag, text);
        }

        @Override
        public void warn(String tag, String text) {
            Log.w(tag, text);
        }

        @Override
        public void error(String tag, String text) {
            Log.e(tag, text);
        }
    };

    public static void inject(HookLog hookLog) {
        mHookLog = hookLog;
    }

    public static void verbose(String tag, String text) {
        mHookLog.verbose(tag, text);
    }

    public static void debug(String tag, String text) {
        mHookLog.debug(tag, text);
    }

    public static void info(String tag, String text) {
        mHookLog.info(tag, text);
    }

    public static void warn(String tag, String text) {
        mHookLog.warn(tag, text);
    }

    public static void error(String tag, String text) {
        mHookLog.error(tag, text);
    }
}
