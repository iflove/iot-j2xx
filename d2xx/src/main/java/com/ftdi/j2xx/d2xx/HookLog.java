package com.ftdi.j2xx.d2xx;

public interface HookLog {
    void verbose(String tag, String text);

    void debug(String tag, String text);

    void info(String tag, String text);

    void warn(String tag, String text);

    void error(String tag, String text);
}