package com.winlator.console;

import java.util.HashMap;
import java.util.Map;

/** Linux stat parser: command names can contain spaces and parentheses. */
public final class ProcessCpuActivity {
    private final Map<String, Long> previous = new HashMap<>();
    private final Map<String, Long> current = new HashMap<>();

    public void beginSample() { current.clear(); }

    public boolean observe(String stat) {
        try {
            int commandEnd = stat.lastIndexOf(')');
            if (commandEnd < 0) return false;
            String pid = stat.substring(0, stat.indexOf(' '));
            String[] fields = stat.substring(commandEnd + 2).trim().split("\\s+");
            if (fields.length < 20 || "Z".equals(fields[0]) || "X".equals(fields[0])) return false;
            // Remaining array starts at stat field 3: utime=14, stime=15, starttime=22.
            long ticks = Math.addExact(Long.parseLong(fields[11]), Long.parseLong(fields[12]));
            String key = pid + ":" + fields[19];
            current.put(key, ticks);
            Long before = previous.get(key);
            return before != null && ticks > before;
        }
        catch (RuntimeException ignored) { return false; }
    }

    public void endSample() {
        previous.clear();
        previous.putAll(current);
    }
}
