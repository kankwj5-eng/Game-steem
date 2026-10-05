package com.winlator.console;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class RuntimeStartupTest {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        String shell = args.length > 0 ? args[0] : "/bin/sh";
        check(RuntimeStartupPolicy.wineDebug(false).equals("-all,err+all"), "Wine error classes");
        check(!RuntimeStartupPolicy.steamWindow(true, true, "explorer.exe", "Steam"), "Desktop is not Steam");
        check(!RuntimeStartupPolicy.steamWindow(false, false, "steam.exe", "Steam"), "Unmapped window");
        check(!RuntimeStartupPolicy.steamWindow(true, false, "", ""), "Map before metadata");
        check(RuntimeStartupPolicy.steamWindow(true, false, "steam.exe", ""), "Late WM_CLASS");
        check(RuntimeStartupPolicy.steamWindow(true, false, "Wine", "Steam - Inicio"), "Late title");

        check(RuntimeStartupPolicy.ownsUidLine("Uid:\t10123\t10123\t10123\t10123", 10123), "Own UID");
        check(!RuntimeStartupPolicy.ownsUidLine("Uid: 10124 10124 10124 10124", 10123), "Other app UID excluded");
        check(!RuntimeStartupPolicy.ownsUidLine("Uid: invalid", 10123), "Unreadable UID excluded");
        ProcessCpuActivity cpu = new ProcessCpuActivity();
        cpu.beginSample();
        check(!cpu.observe(stat(1, 1, 100, "R")), "First sample must not imply CPU work");
        cpu.endSample(); cpu.beginSample();
        check(cpu.observe(stat(2, 1, 100, "R")), "CPU work with unchanged PID and name");
        check(!cpu.observe("broken stat"), "Malformed process");
        cpu.endSample(); cpu.beginSample();
        check(!cpu.observe(stat(500, 20, 200, "R")), "Reused PID must not inherit CPU activity");
        check(!cpu.observe(stat(600, 20, 100, "Z")), "Zombie must not imply work");
        cpu.endSample();

        List<String> lines = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger status = new AtomicInteger(-99);
        CountDownLatch done = new CountDownLatch(1);
        int pid = ObservedProcess.start(new String[]{shell, "-c", "echo stdout; echo stderr >&2; exit 7"},
                null, null, lines::add, value -> { status.set(value); done.countDown(); });
        check(pid > 0, "Real PID must be available");
        check(done.await(5, TimeUnit.SECONDS), "Termination callback deadline");
        check(status.get() == 7, "Preserve actual exit status");
        awaitReaders();
        check(lines.contains("stdout") && lines.contains("stderr"), "Capture both streams");

        AtomicInteger callbacks = new AtomicInteger();
        CountDownLatch failed = new CountDownLatch(1);
        check(ObservedProcess.start(new String[]{"/definitely-missing-droiddeck-program"}, null, null,
                lines::add, value -> { check(value == -1, "Start failure status"); callbacks.incrementAndGet(); failed.countDown(); }) == -1,
                "Missing executable PID");
        check(failed.await(5, TimeUnit.SECONDS), "Start failure must notify");
        check(callbacks.get() == 1, "Exactly one failure callback");
        check(lines.stream().anyMatch(line -> line.startsWith("ERROR al iniciar")), "Visible start failure");
        for (int i = 0; i < 16; i++) {
            CountDownLatch exited = new CountDownLatch(1);
            check(ObservedProcess.start(new String[]{shell, "-c", "exit 0"}, null, null, lines::add,
                    value -> exited.countDown()) > 0, "Repeated launch");
            check(exited.await(5, TimeUnit.SECONDS), "Repeated exit");
        }
        awaitReaders();
        System.out.println("PASS runtime: streams, errors, exit, reader lifetime, Wine diagnostics, late window metadata, CPU activity");
    }
    private static String stat(int user, int system, int started, String state) {
        String[] fields = new String[20];
        java.util.Arrays.fill(fields, "0");
        fields[0] = state; fields[11] = "" + user; fields[12] = "" + system; fields[19] = "" + started;
        return "456 (steam (worker)) " + String.join(" ", fields);
    }
    private static void awaitReaders() throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < until) {
            boolean alive = false;
            for (Thread thread : Thread.getAllStackTraces().keySet())
                if (thread.isAlive() && thread.getName().startsWith("droiddeck-process-")) alive = true;
            if (!alive) return;
            Thread.sleep(20);
        }
        throw new AssertionError("Process readers/waiter leaked after EOF/exit");
    }
}
