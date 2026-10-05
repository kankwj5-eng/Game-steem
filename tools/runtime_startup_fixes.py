"""Auditable fixes to the pinned Winlator process and first-window integration."""


def replace_once(text, old, new):
    if text.count(old) != 1:
        raise SystemExit("Runtime patch anchor drift: " + old[:100])
    return text.replace(old, new, 1)


def apply_runtime_startup_fixes(src):
    java = src / "app/src/main/java/com/winlator"
    helper = java / "core/ProcessHelper.java"
    text = helper.read_text()
    start = text.index("        int pid = -1;", text.index("Callback<Integer> terminationCallback"))
    end = text.index("    public static void removeAllDebugCallbacks()", start)
    text = text[:start] + '''        final boolean observe;
        synchronized (debugCallbacks) { observe = !debugCallbacks.isEmpty(); }
        Map<String, String> variables = new java.util.HashMap<>();
        if (envVars != null) {
            for (String name : envVars) variables.put(name, envVars.get(name));
        }
        return com.winlator.console.ObservedProcess.start(splitCommand(command), variables, workingDir,
                observe ? ProcessHelper::dispatchDebugLine : null,
                terminationCallback == null ? null : terminationCallback::call);
    }

    private static void dispatchDebugLine(String line) {
        ArrayList<Callback<String>> listeners;
        synchronized (debugCallbacks) { listeners = new ArrayList<>(debugCallbacks); }
        for (Callback<String> callback : listeners) callback.call(line);
        if (listeners.isEmpty() && MainActivity.DEBUG_MODE) System.out.println(line);
    }

''' + text[end:]
    text = replace_once(text, '''            catch (Exception e) {
                return Collections.emptyList();
            }
        }

        return result;''', '''            catch (Exception e) {
                // Another UID or a process exiting during the scan must not erase valid rows.
                continue;
            }
        }

        return result;''')
    for unused in ["import java.lang.reflect.Field;\n", "import java.util.concurrent.Executors;\n", "import java.io.InputStream;\n", "import java.io.InputStreamReader;\n"]:
        text = text.replace(unused, "")
    text = replace_once(text, "if (pstat.parentPID == parentPID || pstat.pid > parentPID)",
                        "if (pstat.pid != parentPID && hasAppUid(pstat.pid))")
    text = replace_once(text, "    public static List<PStat> getChildProcesses() {", """    private static boolean hasAppUid(int pid) {
        try (BufferedReader reader = new BufferedReader(new FileReader("/proc/" + pid + "/status"))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("Uid:")) {
                    return com.winlator.console.RuntimeStartupPolicy.ownsUidLine(line, Process.myUid());
                }
            }
        }
        catch (Exception ignored) {}
        return false;
    }

    public static List<PStat> getChildProcesses() {""")
    helper.write_text(text)

    activity = java / "XServerDisplayActivity.java"
    text = activity.read_text()
    text = replace_once(text, "new RuntimeConsoleOverlay(this)", "new RuntimeConsoleOverlay(this, () -> finishDroidDeckRuntime(125))")
    text = replace_once(text, '''                if (window.id == frameRatingWindowId) frameRating.update();
            }

            @Override
            public void onMapWindow(Window window) {
                if (!flags[0] && window.isRenderable() && !window.getClassName().isEmpty()) {''', '''                if (window.id == frameRatingWindowId) frameRating.update();
                checkFirstWindow(window);
            }

            @Override
            public void onModifyWindowProperty(Window window, com.winlator.xserver.Property property) {
                checkFirstWindow(window);
            }

            @Override
            public void onUpdateWindowGeometry(Window window, boolean resized) {
                checkFirstWindow(window);
            }

            private void checkFirstWindow(Window window) {
                boolean steamClient = droidDeckConsoleMode && "steam_client".equals(getIntent().getStringExtra("droiddeck_purpose"));
                boolean ready = steamClient
                        ? com.winlator.console.RuntimeStartupPolicy.steamWindow(window.isRenderable(), window.isDesktopWindow(), window.getClassName(), window.getName())
                        : window.isRenderable() && !window.getClassName().isEmpty();
                if (!flags[0] && ready) {''')
    text = replace_once(text, '''                    flags[0] = true;
                }

                if (flags[1]''', '''                    flags[0] = true;
                }
            }

            @Override
            public void onMapWindow(Window window) {
                checkFirstWindow(window);
                if (flags[1]''')
    text = replace_once(text, "        guestProgramLauncherComponent.setEnvVars(envVars);", '''        if (droidDeckConsoleMode) {
            String wineDebug = com.winlator.console.RuntimeStartupPolicy.wineDebug(preferences.getBoolean("droiddeck_detailed_logs", false));
            envVars.put("WINEDEBUG", wineDebug);
            ConsoleLogStore.info("WINEDEBUG efectivo: " + wineDebug);
        }
        guestProgramLauncherComponent.setEnvVars(envVars);''')
    text = replace_once(text, "        Executors.newSingleThreadExecutor().execute(() -> {\n            if (!isGenerateWineprefix())", "        new Thread(() -> {\n            try {\n            if (isFinishing() || isDestroyed()) return;\n            if (!isGenerateWineprefix())")
    text = replace_once(text, "            setupXEnvironment();\n        });", """            setupXEnvironment();
            }
            catch (Exception | LinkageError | OutOfMemoryError error) {
                ConsoleLogStore.error("Preparación del motor falló: " + error);
                runOnUiThread(() -> { if (!isFinishing() && !isDestroyed()) finishDroidDeckRuntime(126); });
            }
        }, "droiddeck-runtime-setup").start();""")
    activity.write_text(text)

    handler = java / "winhandler/WinHandler.java"
    text = handler.read_text()
    text = replace_once(text, '''            if (!sendPacket(CLIENT_PORT) && onGetProcessInfoListener != null) {
                onGetProcessInfoListener.onGetProcessInfo(0, 0, null);
            }''', '''            // Only an actual reply may notify the listener; send failure is not a reply.
            sendPacket(CLIENT_PORT);''')
    handler.write_text(text)
