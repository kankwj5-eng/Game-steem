package com.winlator.console;

import java.util.Locale;

public final class RuntimeStartupPolicy {
    private RuntimeStartupPolicy() {}

    public static String wineDebug(boolean detailed) {
        return detailed ? "-all,err+all,warn+all,+seh,+loaddll" : "-all,err+all";
    }

    public static boolean ownsUidLine(String line, int uid) {
        if (line == null || !line.startsWith("Uid:")) return false;
        try {
            String[] values = line.substring(4).trim().split("\\s+");
            return values.length > 0 && Integer.parseInt(values[0]) == uid;
        }
        catch (RuntimeException ignored) { return false; }
    }

    public static boolean steamWindow(boolean renderable, boolean desktop, String className, String title) {
        if (!renderable || desktop) return false;
        return containsSteam(className) || containsSteam(title);
    }

    private static boolean containsSteam(String value) {
        return value != null && value.toLowerCase(Locale.ROOT).contains("steam");
    }
}
