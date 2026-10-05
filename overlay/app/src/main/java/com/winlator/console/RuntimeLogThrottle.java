package com.winlator.console;

/** Keeps the first diagnostic in each interval, folds repeats, bounds default log work. */
public final class RuntimeLogThrottle {
    private final int limit;
    private boolean initialized;
    private long windowStart;
    private int accepted;
    private long suppressed;
    private String previous;

    public RuntimeLogThrottle(int linesPerSecond) {
        if (linesPerSecond < 1) throw new IllegalArgumentException("Positive log budget required");
        limit = linesPerSecond;
    }

    public synchronized String accept(String line, long now) {
        if (line == null || line.isEmpty()) return null;
        String summary = null;
        if (!initialized || now < windowStart || now - windowStart >= 1000L) {
            summary = takeSummary();
            initialized = true;
            windowStart = now;
            accepted = 0;
            previous = null;
        }
        if (line.equals(previous) || accepted >= limit) {
            suppressed++;
            return null;
        }
        previous = line;
        accepted++;
        return summary == null ? line : summary + "\n" + line;
    }

    public synchronized String takeSummary() {
        if (suppressed == 0) return null;
        String summary = "REGISTRO · " + suppressed + " mensajes repetidos o excedentes agrupados";
        suppressed = 0;
        return summary;
    }

    public synchronized void reset() {
        initialized = false;
        accepted = 0;
        suppressed = 0;
        previous = null;
    }
}
