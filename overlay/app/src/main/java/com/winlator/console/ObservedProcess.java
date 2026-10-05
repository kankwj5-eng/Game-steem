package com.winlator.console;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.function.Consumer;

/** Bounded-lifetime readers and exactly one termination notification per launch. */
public final class ObservedProcess {
    private ObservedProcess() {}

    public static int start(String[] command, Map<String, String> variables, File directory,
                            Consumer<String> output, Consumer<Integer> exited) {
        java.lang.Process process;
        try {
            ProcessBuilder builder = new ProcessBuilder(command).directory(directory);
            if (variables != null) builder.environment().putAll(variables);
            if (output == null) builder.redirectOutput(new File("/dev/null")).redirectErrorStream(true);
            process = builder.start();
        }
        catch (Exception error) {
            if (output != null) output.accept("ERROR al iniciar proceso: " + error);
            if (exited != null) thread("droiddeck-start-error", () -> exited.accept(-1));
            return -1;
        }

        // Readers and waiter are attached even if Android's PID reflection fails.
        if (output != null) {
            read(process.getInputStream(), output);
            read(process.getErrorStream(), output);
        }
        thread("droiddeck-process-wait", () -> {
            try {
                int status = process.waitFor();
                if (exited != null) exited.accept(status);
            }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); }
        });
        try {
            // Java 9+ on the host; Android also supports its concrete private pid field.
            try { return ((Number)java.lang.Process.class.getMethod("pid").invoke(process)).intValue(); }
            catch (ReflectiveOperationException ignored) {
                Field field = process.getClass().getDeclaredField("pid");
                field.setAccessible(true);
                try { return field.getInt(process); }
                finally { field.setAccessible(false); }
            }
        }
        catch (Exception error) {
            if (output != null) output.accept("ERROR al obtener PID; cancelando proceso: " + error);
            process.destroy();
            return -1;
        }
    }

    private static void read(InputStream stream, Consumer<String> output) {
        thread("droiddeck-process-output", () -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream))) {
                String line;
                while ((line = reader.readLine()) != null) output.accept(line);
            }
            catch (java.io.IOException ignored) { /* EOF/close during shutdown. */ }
        });
    }

    private static void thread(String name, Runnable work) {
        Thread thread = new Thread(work, name);
        thread.setDaemon(true);
        thread.start();
    }
}
