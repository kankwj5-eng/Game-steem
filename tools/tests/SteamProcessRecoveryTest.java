package com.winlator.console;

import java.io.File;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class SteamProcessRecoveryTest {
    private static void check(boolean ok, String reason) { if (!ok) throw new AssertionError(reason); }
    public static void main(String[] args) throws Exception {
        File root = Files.createTempDirectory("steam-recovery-test").toFile();
        try {
            row(root, 100, 123, "/root/steam", "R", 90);
            row(root, 101, 124, "/root/steam", "R", 90);
            row(root, 102, 123, "/other/steam", "R", 90);
            row(root, 103, 123, null, "R", 90);
            row(root, 104, 123, "/root/steam", "Z", 90);
            row(root, 105, 123, "/root/steam", "R", 90);
            new File(root, "105/environ").delete();
            row(root, 106, 123, "/root/steam", "R", 90);
            row(root, 107, 123, "/root/steam-extra", "R", 90);
            row(root, 108, 123, "/root/steam", "R", 90);
            write(new File(root, "108/stat"), "invalid stat");
            row(root, 109, 123, "/root/steam", "R", 90);
            write(new File(root, "109/environ"), String.join("", Collections.nCopies(70000, "x")));
            List<Integer> killed = new ArrayList<>();
            check(SteamProcessRecovery.recover(root, 123, 106, "/root/steam", killed::add) == 1, "Exactly one owned tagged guest");
            check(killed.equals(Collections.singletonList(100)), "Never kill installer, self, other uid, root, zombie or unreadable row");
            check(SteamProcessRecovery.recover(root, 123, 106, "/root/steam", pid -> { throw new IllegalStateException(); }) == 0,
                    "Denied signal is not reported as reclaimed");
            check(SteamProcessRecovery.recover(root, 123, 106, "", killed::add) == 0, "No wildcard scope");
            testReal(args.length > 0 ? args[0] : "/bin/sh");
            System.out.println("PASS recovery: real tagged child stopped; untagged child preserved; UID/root/self/zombie/read limits");
        } finally { remove(root); }
    }
    private static void testReal(String shell) throws Exception {
        String scope = "probe-" + java.util.UUID.randomUUID();
        CountDownLatch taggedExit = new CountDownLatch(1), otherExit = new CountDownLatch(1);
        int tagged = ObservedProcess.start(new String[]{shell, "-c", "exec sleep 30"},
                Collections.singletonMap(SteamProcessRecovery.ENV, scope), null, null, s -> taggedExit.countDown());
        int other = ObservedProcess.start(new String[]{shell, "-c", "exec sleep 30"}, null, null, null, s -> otherExit.countDown());
        try {
            check(tagged > 1 && other > 1, "Launch real children");
            String status = new String(Files.readAllBytes(new File("/proc/self/status").toPath()), StandardCharsets.UTF_8);
            int uid = -1, self = -1;
            for (String line : status.split("\n")) {
                if (line.startsWith("Uid:")) uid = Integer.parseInt(line.substring(4).trim().split("\\s+")[0]);
                if (line.startsWith("Pid:")) self = Integer.parseInt(line.substring(4).trim());
            }
            check(uid >= 0 && self > 1, "Read app UID and PID");
            // Wait until the shell exec has settled: the environment tag must still be inherited by sleep.
            Thread.sleep(100);
            check(SteamProcessRecovery.recover(new File("/proc"), uid, self, scope, p -> { check(namespacePid(p) == tagged, "Selected PID must match tagged child"); signal(shell, tagged); }) == 1, "Recover real tagged child");
            check(taggedExit.await(5, TimeUnit.SECONDS), "Tagged child actually exits");
            check(!otherExit.await(100, TimeUnit.MILLISECONDS), "Untagged child survives recovery");
        } finally { if (tagged > 1 && taggedExit.getCount() > 0) signal(shell, tagged); if (other > 1 && otherExit.getCount() > 0) signal(shell, other); }
        check(otherExit.await(5, TimeUnit.SECONDS), "Test helper cleanup");
    }
    // Host runner can mount /proc from its parent PID namespace. Android proc and signal PIDs agree.
    private static int namespacePid(int procPid) {
        try {
            for (String line : Files.readAllLines(new File("/proc/" + procPid + "/status").toPath())) {
                if (line.startsWith("NSpid:")) {
                    String[] ids = line.substring(6).trim().split("\\s+");
                    return Integer.parseInt(ids[ids.length - 1]);
                }
            }
        } catch (Exception error) { throw new IllegalStateException(error); }
        return procPid;
    }
    private static void signal(String shell, int pid) {
        try { java.lang.Process process = new ProcessBuilder(shell, "-c", "kill -9 " + pid).start();
            int status = process.waitFor();
            if (status != 0 && new File("/proc/" + pid).exists()) throw new IllegalStateException("signal status " + status); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }
    private static void row(File root, int pid, int uid, String scope, String state, int start) throws Exception {
        File dir = new File(root, "" + pid); dir.mkdirs();
        write(new File(dir, "status"), "Uid: " + uid + " " + uid + " " + uid + " " + uid + "\n");
        write(new File(dir, "environ"), scope == null ? "PATH=/bin\u0000" : SteamProcessRecovery.ENV + "=" + scope + "\u0000");
        String[] fields = new String[20]; java.util.Arrays.fill(fields, "0"); fields[0] = state; fields[19] = "" + start;
        write(new File(dir, "stat"), pid + " (steam (child)) " + String.join(" ", fields));
    }
    private static void write(File file, String content) throws Exception { Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8)); }
    private static void remove(File file) { File[] children = file.listFiles(); if (children != null) for (File child : children) remove(child); file.delete(); }
}
