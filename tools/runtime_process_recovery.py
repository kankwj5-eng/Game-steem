"""Small, checked lifecycle patch for the pinned Winlator guest launcher."""
from runtime_startup_fixes import replace_once


def apply_runtime_process_recovery(src):
    path = src / "app/src/main/java/com/winlator/xenvironment/components/GuestProgramLauncherComponent.java"
    text = path.read_text()
    text = replace_once(text, "    private static int pid = -1;", "    private static int pid = -1;\n    private static int generation;\n    private static String recoveryScope;")
    text = replace_once(text, "            if (pid != -1) {\n                Process.killProcess(pid);\n                pid = -1;\n            }", """            ++generation;
            if (pid != -1) {
                try { Process.killProcess(pid); }
                catch (RuntimeException ignored) {}
                pid = -1;
            }
            reclaimSteamProcesses();""")
    text = replace_once(text, "    public Callback<Integer> getTerminationCallback() {", """    private static void reclaimSteamProcesses() {
        int count = com.winlator.console.SteamProcessRecovery.recover(new File("/proc"),
                Process.myUid(), Process.myPid(), recoveryScope, Process::killProcess);
        if (count > 0) com.winlator.console.ConsoleLogStore.info(
                "RECUPERACIÓN · " + count + " procesos anteriores del entorno Steam: cierre solicitado");
    }

    public Callback<Integer> getTerminationCallback() {""")
    text = replace_once(text, "        String command = rootDir+\"/usr/local/bin/box64 \"+guestExecutable;", """        // The tag is inherited by Wine/Steam children; installers and Android helpers are untagged.
        recoveryScope = rootDir.getAbsolutePath();
        reclaimSteamProcesses();
        envVars.put(com.winlator.console.SteamProcessRecovery.ENV, recoveryScope);
        final int launchedGeneration = ++generation;
        String command = rootDir+"/usr/local/bin/box64 "+guestExecutable;""")
    text = replace_once(text, "            synchronized (lock) {\n                pid = -1;\n            }\n            if (terminationCallback", "            synchronized (lock) {\n                if (generation != launchedGeneration) return;\n                pid = -1;\n            }\n            if (terminationCallback")
    path.write_text(text)
