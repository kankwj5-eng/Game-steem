#!/usr/bin/env python3
"""Check stock desktop wiring and execute the actual WinHandler INIT/EXEC queue on JVM."""
import os
from pathlib import Path
import subprocess
import sys
import tempfile

src = Path(sys.argv[1])
java = src / 'app/src/main/java/com/winlator'
activity = (java / 'XServerDisplayActivity.java').read_text()
controller = (java / 'console/ConsoleBootstrapController.java').read_text()
guest = (java / 'xenvironment/components/GuestProgramLauncherComponent.java').read_text()
handler = (java / 'winhandler/WinHandler.java').read_text()


def block(text, anchor):
    start = text.index(anchor)
    opening = text.index('{', start)
    depth = 1
    end = opening + 1
    while depth:
        depth += (text[end] == '{') - (text[end] == '}')
        end += 1
    return text[start:end]


assert 'intent.putExtra("exec_path"' not in controller
assert 'intent.putExtra("exec_dos_path", "C:\\\\Program Files (x86)\\\\Steam\\\\steam.exe")' in controller
assert 'if (isDroidDeckSteamDesktop())' in block(activity, 'private String getWineStartCommand()')
assert 'cmdArgs = "/dir C:\\\\windows \\\"wfm.exe\\\""' in activity
assert 'winHandler.exec(steamPath, null);' in activity
assert 'runtimeActive || runtimeFailed' in controller
assert 'reclaimSteamProcesses' not in guest
assert 'SteamProcessRecovery.ENV' not in guest
stock = subprocess.check_output(['git', '-C', str(src), 'show', 'HEAD:app/src/main/java/com/winlator/XServerDisplayActivity.java'], text=True)
for line in ['String desktopName =', 'String guestExecutable =']:
    assert next(x.strip() for x in stock.splitlines() if line in x) == next(x.strip() for x in activity.splitlines() if line in x)

# Compile the upstream queue, EXEC encoding and INIT block verbatim. This catches
# premature delivery, paths split at spaces and duplicate delivery after a second INIT.
methods = '\n'.join(block(handler, x) for x in ['public void exec(final String filename', 'protected void addAction', 'private void startSendThread'])
init = block(handler, 'case RequestCodes.INIT:').removeprefix('case RequestCodes.INIT:')
code = r'''
import java.nio.*;
import java.util.*;
import java.util.concurrent.*;
public class DesktopQueueProbe {
    private final ByteBuffer sendData = ByteBuffer.allocate(256).order(ByteOrder.LITTLE_ENDIAN);
    private final ArrayDeque<Runnable> actions = new ArrayDeque<>();
    private boolean running = true, initReceived;
    private static final int CLIENT_PORT = 7946;
    private static class RequestCodes { static final byte EXEC = 2; }
    private final CountDownLatch sent = new CountDownLatch(1);
    private volatile int count;
    private String observed;
    private boolean sendPacket(int port) {
        ByteBuffer data = sendData.duplicate().order(ByteOrder.LITTLE_ENDIAN); data.flip();
        if(data.get() != RequestCodes.EXEC) throw new AssertionError("Wrong request");
        byte[] path = new byte[data.getInt()]; data.get(path);
        if(data.getInt() != 0) throw new AssertionError("Unexpected arguments");
        observed = new String(path); count++; sent.countDown(); return true;
    }
    void handshake() { __INIT_BLOCK__ }
    __QUEUE_METHODS__
    public static void main(String[] ignored) throws Exception {
        DesktopQueueProbe q = new DesktopQueueProbe();
        String path = "C:\\Program Files (x86)\\Steam\\steam.exe";
        q.startSendThread(); q.exec(path, null);
        if(q.sent.await(100, TimeUnit.MILLISECONDS)) throw new AssertionError("EXEC before INIT");
        q.handshake();
        if(!q.sent.await(3, TimeUnit.SECONDS) || !path.equals(q.observed)) throw new AssertionError("Missing or damaged path");
        q.handshake();
        synchronized(q.actions) { if(q.count != 1 || !q.actions.isEmpty()) throw new AssertionError("Duplicate EXEC"); }
        System.out.println("PASS stock shell command; actual WinHandler queue: waits INIT, preserves spaces, sends once");
        System.exit(0);
    }
}
'''.replace('__INIT_BLOCK__', init.replace('break;', '')).replace('__QUEUE_METHODS__', methods)
with tempfile.TemporaryDirectory() as directory:
    file = Path(directory) / 'DesktopQueueProbe.java'
    file.write_text(code)
    subprocess.run([os.environ.get('JAVAC', 'javac'), '-d', directory, str(file)], check=True)
    subprocess.run(['java', '-cp', directory, 'DesktopQueueProbe'], check=True, timeout=10)
