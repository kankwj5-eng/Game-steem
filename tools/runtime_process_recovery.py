"""Ignore callbacks from a stopped Winlator launch without killing Wine descendants."""
from runtime_startup_fixes import replace_once


def apply_runtime_process_recovery(src):
    path = src / "app/src/main/java/com/winlator/xenvironment/components/GuestProgramLauncherComponent.java"
    text = path.read_text()
    text = replace_once(text, "    private static int pid = -1;", "    private static int pid = -1;\n    private static int generation;")
    text = replace_once(text, "            if (pid != -1) {\n                Process.killProcess(pid);", "            ++generation;\n            if (pid != -1) {\n                Process.killProcess(pid);")
    text = replace_once(text, '        String command = rootDir+"/usr/local/bin/box64 "+guestExecutable;', '        final int launchedGeneration = ++generation;\n        String command = rootDir+"/usr/local/bin/box64 "+guestExecutable;')
    text = replace_once(text, "            synchronized (lock) {\n                pid = -1;\n            }\n            if (terminationCallback", "            synchronized (lock) {\n                if (generation != launchedGeneration) return;\n                pid = -1;\n            }\n            if (terminationCallback")
    path.write_text(text)
