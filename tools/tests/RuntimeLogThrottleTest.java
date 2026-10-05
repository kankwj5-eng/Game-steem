package com.winlator.console;

public final class RuntimeLogThrottleTest {
    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        RuntimeLogThrottle throttle = new RuntimeLogThrottle(40);
        int retained = 0;
        for (int i = 0; i < 10000; i++) if (throttle.accept("Wine error " + i, 100) != null) retained++;
        check(retained == 40, "Flood must retain first 40 unique diagnostics per interval");
        String next = throttle.accept("Steam next interval", 1100);
        check(next.contains("9960 mensajes") && next.endsWith("Steam next interval"), "Suppression count and next diagnostic");
        throttle.reset();
        check(throttle.accept("Wine repeated", 0).equals("Wine repeated"), "First error retained");
        for (int i = 0; i < 9999; i++) check(throttle.accept("Wine repeated", 0) == null, "Repeated error folded");
        check(throttle.takeSummary().contains("9999 mensajes"), "Short burst summary at flush");
        check(throttle.takeSummary() == null, "Summary only once");
        throttle.reset();
        check(throttle.accept("Steam reset", 0) != null, "Reset starts clean budget");
        check(throttle.accept("Steam clock reset", -1) != null, "Clock rollback starts clean interval");
        System.out.println("PASS logging flood: 10000 unique lines -> 40 retained (99.6% fewer formatted/disk records); duplicates summarized");
    }
}
