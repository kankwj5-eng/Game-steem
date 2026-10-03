package com.winlator.console;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.winlator.container.Container;
import com.winlator.core.FileUtils;
import com.winlator.services.ForegroundService;

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class SteamLegacyInstaller {
    public static final String ASSET_URL =
            "https://github.com/brunodev85/winlator-addons/releases/download/v1.0.0/steam-legacy.7z";

    // Se completa con el digest verificado por el job steam_contract antes de la entrega final.
    public static final String EXPECTED_SHA256 = "f5771fed575afb8ef8a133ee28e34a6b4191a366943d0ff7505eab3846b3d19c";

    public enum Phase {
        IDLE,
        DOWNLOADING,
        VERIFYING,
        INDEXING,
        EXTRACTING,
        FINALIZING,
        READY,
        ERROR
    }

    public static final class State {
        public final Phase phase;
        public final String detail;
        public final int progress;
        public final long currentBytes;
        public final long totalBytes;
        public final String currentFile;

        State(Phase phase, String detail, int progress, long currentBytes, long totalBytes, String currentFile) {
            this.phase = phase;
            this.detail = detail;
            this.progress = progress;
            this.currentBytes = currentBytes;
            this.totalBytes = totalBytes;
            this.currentFile = currentFile == null ? "" : currentFile;
        }
    }

    public interface Listener {
        void onInstallerState(State state);
    }

    private static final AtomicBoolean running = new AtomicBoolean(false);
    private static final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private static final ExecutorService worker = Executors.newSingleThreadExecutor();
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static volatile State state = new State(Phase.IDLE, "Esperando", 0, 0, 0, "");

    private SteamLegacyInstaller() {}

    public static void addListener(Listener listener) {
        if (listener == null) return;
        if (!listeners.contains(listener)) listeners.add(listener);
        State snapshot = state;
        main.post(() -> listener.onInstallerState(snapshot));
    }

    public static void removeListener(Listener listener) {
        if (listener != null) listeners.remove(listener);
    }

    public static boolean isRunning() {
        return running.get();
    }

    public static State getState() {
        return state;
    }

    public static boolean start(Context context, Container container) {
        if (context == null || container == null) return false;
        if (!running.compareAndSet(false, true)) return false;

        Context app = context.getApplicationContext();
        ForegroundService.startInstallerSession(app);
        worker.execute(() -> runInstall(app, container));
        return true;
    }

    private static void runInstall(Context context, Container container) {
        try {
            File driveC = new File(container.getRootDir(), ".wine/drive_c");
            File steamExe = new File(driveC, "Program Files (x86)/Steam/steam.exe");
            if (steamExe.isFile()) {
                publish(Phase.READY, "Steam ya está instalado", 100, steamExe.length(), steamExe.length(), "C:\\Program Files (x86)\\Steam\\steam.exe");
                ConsoleLogStore.ok("Steam ya estaba instalado: " + steamExe.getAbsolutePath());
                return;
            }

            File tempDir = new File(driveC, "windows/temp");
            if (!tempDir.isDirectory() && !tempDir.mkdirs()) {
                throw new IllegalStateException("No se pudo crear C:\\windows\\temp");
            }

            File archive = new File(tempDir, "winlator-addon.7z");
            File partial = new File(tempDir, "winlator-addon.7z.part");

            ConsoleLogStore.info("STEAM LEGACY · fuente Winlator 11.2: " + ASSET_URL);
            ConsoleLogStore.info("CACHE · C:\\windows\\temp\\winlator-addon.7z");

            if (!isUsableArchive(archive)) {
                downloadWithRetry(partial, archive);
            }
            else {
                ConsoleLogStore.ok("CACHE · paquete Steam Legacy reutilizable · " + formatBytes(archive.length()));
            }

            publish(Phase.VERIFYING, "Validando integridad del paquete", 50, archive.length(), archive.length(), "steam-legacy.7z");
            String sha256 = sha256(archive);
            ConsoleLogStore.info("SHA-256 Steam Legacy: " + sha256);
            if (!EXPECTED_SHA256.isEmpty() && !EXPECTED_SHA256.equalsIgnoreCase(sha256)) {
                ConsoleLogStore.warn("CACHE CORRUPTA · SHA-256 no coincide; descartando paquete y reintentando desde cero");
                if (archive.exists() && !archive.delete()) {
                    throw new IllegalStateException("No se pudo eliminar la caché corrupta de Steam Legacy");
                }
                if (partial.exists() && !partial.delete()) {
                    throw new IllegalStateException("No se pudo eliminar la descarga parcial corrupta");
                }

                publish(Phase.DOWNLOADING, "Caché corrupta descartada · descargando copia limpia", 1, 0, 0, "steam-legacy.7z");
                downloadWithRetry(partial, archive);

                publish(Phase.VERIFYING, "Revalidando copia limpia", 50, archive.length(), archive.length(), "steam-legacy.7z");
                sha256 = sha256(archive);
                ConsoleLogStore.info("SHA-256 Steam Legacy (copia limpia): " + sha256);
                if (!EXPECTED_SHA256.equalsIgnoreCase(sha256)) {
                    throw new SecurityException("SHA-256 inesperado incluso tras una descarga limpia");
                }
                ConsoleLogStore.ok("INTEGRIDAD · la copia limpia coincide con el SHA-256 fijado");
            }

            ArchiveIndex index = indexArchive(archive);
            if (!index.containsSteamExe) {
                throw new IllegalStateException("El paquete no contiene Steam/steam.exe");
            }
            ConsoleLogStore.ok("PAQUETE · " + index.entries + " entradas · " + formatBytes(index.uncompressedBytes)
                    + " sin comprimir · steam.exe confirmado");

            long usableBytes = driveC.getUsableSpace();
            long safetyMargin = 128L * 1024L * 1024L;
            long requiredBytes = index.uncompressedBytes + safetyMargin;
            ConsoleLogStore.info("ALMACENAMIENTO · libre " + formatBytes(usableBytes)
                    + " · requerido aprox. " + formatBytes(requiredBytes));
            if (usableBytes > 0L && usableBytes < requiredBytes) {
                throw new IllegalStateException("Espacio insuficiente: libres " + formatBytes(usableBytes)
                        + ", necesarios aprox. " + formatBytes(requiredBytes));
            }

            File targetRoot = new File(driveC, "Program Files (x86)");
            if (!targetRoot.isDirectory() && !targetRoot.mkdirs()) {
                throw new IllegalStateException("No se pudo preparar C:\\Program Files (x86)");
            }

            File steamDir = new File(targetRoot, "Steam");
            if (steamDir.exists() && !steamExe.isFile()) {
                ConsoleLogStore.warn("LIMPIEZA · instalación parcial anterior detectada; eliminando C:\\Program Files (x86)\\Steam");
                FileUtils.delete(steamDir);
            }

            extractArchive(archive, targetRoot, index);

            publish(Phase.FINALIZING, "Verificando instalación final", 98, index.uncompressedBytes, index.uncompressedBytes,
                    "C:\\Program Files (x86)\\Steam\\steam.exe");

            if (!steamExe.isFile() || steamExe.length() < 256 * 1024L) {
                throw new IllegalStateException("La extracción terminó, pero steam.exe no quedó válido");
            }

            File marker = new File(steamDir, ".droiddeck-source");
            try (FileOutputStream out = new FileOutputStream(marker, false)) {
                String markerText = "source=" + ASSET_URL + "\nsha256=" + sha256 + "\n";
                out.write(markerText.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                out.getFD().sync();
            }

            ConsoleLogStore.ok("STEAM LISTO · " + steamExe.getAbsolutePath() + " · " + formatBytes(steamExe.length()));
            publish(Phase.READY, "Steam Legacy instalado y verificado", 100,
                    index.uncompressedBytes, index.uncompressedBytes,
                    "C:\\Program Files (x86)\\Steam\\steam.exe");
        }
        catch (Throwable error) {
            String message = error.getMessage();
            if (message == null || message.trim().isEmpty()) message = error.getClass().getSimpleName();
            ConsoleLogStore.error("STEAM INSTALL · " + error.getClass().getSimpleName() + " · " + message);
            publish(Phase.ERROR, message, 0, 0, 0, "");
        }
        finally {
            running.set(false);
            ForegroundService.stopInstallerSession(context);
        }
    }

    private static void downloadWithRetry(File partial, File archive) throws Exception {
        Exception last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                downloadOnce(partial, archive, attempt);
                return;
            }
            catch (Exception error) {
                last = error;
                ConsoleLogStore.warn("DESCARGA intento " + attempt + "/3 · " + error.getMessage());
                if (attempt < 3) {
                    try {
                        Thread.sleep(900L * attempt);
                    }
                    catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw interrupted;
                    }
                }
            }
        }
        throw last != null ? last : new IllegalStateException("Descarga fallida");
    }

    private static void downloadOnce(File partial, File archive, int attempt) throws Exception {
        long existing = partial.isFile() ? partial.length() : 0L;
        HttpURLConnection connection = (HttpURLConnection)new URL(ASSET_URL).openConnection();
        connection.setConnectTimeout(20_000);
        connection.setReadTimeout(90_000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "DroidDeck/1.0 Winlator-11.2");
        connection.setRequestProperty("Accept-Encoding", "identity");
        if (existing > 0L) connection.setRequestProperty("Range", "bytes=" + existing + "-");
        connection.connect();

        int code = connection.getResponseCode();
        boolean resumed = existing > 0L && code == HttpURLConnection.HTTP_PARTIAL;
        if (code / 100 != 2) {
            connection.disconnect();
            throw new IllegalStateException("HTTP " + code + " al descargar Steam Legacy");
        }
        if (!resumed) existing = 0L;

        long bodyLength = connection.getContentLengthLong();
        long expected = bodyLength > 0 ? existing + bodyLength : -1L;
        ConsoleLogStore.info("HTTP " + code + " · intento " + attempt + "/3"
                + (resumed ? " · reanudando " + formatBytes(existing) : " · descarga nueva")
                + (expected > 0 ? " · total " + formatBytes(expected) : ""));

        long lastUi = 0L;
        long sampleBytes = existing;
        long sampleTime = System.currentTimeMillis();
        int lastLog = -10;

        try (InputStream raw = new BufferedInputStream(connection.getInputStream(), 1024 * 1024);
             FileOutputStream out = new FileOutputStream(partial, resumed)) {
            byte[] buffer = new byte[1024 * 1024];
            long total = existing;
            int read;
            while ((read = raw.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                total += read;

                long now = System.currentTimeMillis();
                if (now - lastUi >= 180L) {
                    long dt = Math.max(1L, now - sampleTime);
                    long delta = Math.max(0L, total - sampleBytes);
                    double speed = delta * 1000.0 / dt;
                    sampleBytes = total;
                    sampleTime = now;
                    lastUi = now;

                    int rawPct = expected > 0 ? Math.min(100, (int)((100L * total) / expected)) : 0;
                    int uiPct = expected > 0 ? Math.max(1, Math.min(48, (rawPct * 48) / 100)) : 8;
                    String detail = expected > 0
                            ? "Descargando Steam Legacy · " + rawPct + "% · " + formatBytes(total) + " / "
                                    + formatBytes(expected) + " · " + formatRate(speed)
                            : "Descargando Steam Legacy · " + formatBytes(total) + " · " + formatRate(speed);
                    publish(Phase.DOWNLOADING, detail, uiPct, total, expected, "steam-legacy.7z");

                    if (rawPct >= lastLog + 10) {
                        lastLog = rawPct;
                        ConsoleLogStore.info("DESCARGA · " + detail);
                    }
                }
            }
            out.getFD().sync();
        }
        finally {
            connection.disconnect();
        }

        long finalSize = partial.length();
        if (expected > 0 && finalSize != expected) {
            throw new IllegalStateException("Descarga incompleta: " + formatBytes(finalSize) + " / " + formatBytes(expected));
        }
        if (!hasSevenZipSignature(partial)) {
            throw new IllegalStateException("El archivo descargado no tiene firma 7z válida");
        }

        if (archive.exists() && !archive.delete()) {
            throw new IllegalStateException("No se pudo reemplazar el paquete anterior");
        }
        if (!partial.renameTo(archive)) {
            throw new IllegalStateException("No se pudo finalizar winlator-addon.7z");
        }
        ConsoleLogStore.ok("DESCARGA COMPLETA · " + formatBytes(archive.length()));
    }

    private static ArchiveIndex indexArchive(File archive) throws Exception {
        publish(Phase.INDEXING, "Leyendo contenido real del paquete", 52, 0, archive.length(), "steam-legacy.7z");

        long bytes = 0L;
        int entries = 0;
        boolean steamExe = false;

        try (SevenZFile sevenZ = new SevenZFile(archive)) {
            SevenZArchiveEntry entry;
            while ((entry = sevenZ.getNextEntry()) != null) {
                entries++;
                String name = normalizeEntryName(entry.getName());
                if (!entry.isDirectory() && entry.getSize() > 0) bytes += entry.getSize();
                if ("steam/steam.exe".equalsIgnoreCase(name)) steamExe = true;

                if (entries <= 20 || entries % 50 == 0 || name.toLowerCase(Locale.US).endsWith("steam.exe")) {
                    ConsoleLogStore.append("PKG", "#" + entries + " · " + name
                            + (entry.isDirectory() ? " [dir]" : " · " + formatBytes(Math.max(0L, entry.getSize()))));
                }
            }
        }

        return new ArchiveIndex(entries, bytes, steamExe);
    }

    private static void extractArchive(File archive, File targetRoot, ArchiveIndex index) throws Exception {
        publish(Phase.EXTRACTING, "Extrayendo Steam Legacy", 55, 0, index.uncompressedBytes, "");

        String canonicalRoot = targetRoot.getCanonicalPath();
        if (!canonicalRoot.endsWith(File.separator)) canonicalRoot += File.separator;

        long extracted = 0L;
        long lastUi = 0L;
        int fileIndex = 0;

        try (SevenZFile sevenZ = new SevenZFile(archive)) {
            SevenZArchiveEntry entry;
            byte[] buffer = new byte[1024 * 1024];

            while ((entry = sevenZ.getNextEntry()) != null) {
                String name = normalizeEntryName(entry.getName());
                if (name.isEmpty()) continue;

                File output = new File(targetRoot, name);
                String canonicalOutput = output.getCanonicalPath();
                if (!canonicalOutput.equals(targetRoot.getCanonicalPath())
                        && !canonicalOutput.startsWith(canonicalRoot)) {
                    throw new SecurityException("Ruta insegura dentro del paquete: " + name);
                }

                if (entry.isDirectory()) {
                    if (!output.isDirectory() && !output.mkdirs()) {
                        throw new IllegalStateException("No se pudo crear " + name);
                    }
                    continue;
                }

                File parent = output.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                    throw new IllegalStateException("No se pudo crear " + parent.getAbsolutePath());
                }

                fileIndex++;
                ConsoleLogStore.append("FILE", "EXTRACT " + fileIndex + "/" + index.entries + " · " + name
                        + " · " + formatBytes(Math.max(0L, entry.getSize())));

                try (FileOutputStream out = new FileOutputStream(output, false)) {
                    int read;
                    while ((read = sevenZ.read(buffer)) > 0) {
                        out.write(buffer, 0, read);
                        extracted += read;

                        long now = System.currentTimeMillis();
                        if (now - lastUi >= 150L) {
                            lastUi = now;
                            int rawPct = index.uncompressedBytes > 0
                                    ? Math.min(100, (int)((100L * extracted) / index.uncompressedBytes))
                                    : 0;
                            int uiPct = 55 + Math.min(40, (rawPct * 40) / 100);
                            String detail = "Extrayendo " + rawPct + "% · " + formatBytes(extracted) + " / "
                                    + formatBytes(index.uncompressedBytes) + " · " + name;
                            publish(Phase.EXTRACTING, detail, uiPct, extracted, index.uncompressedBytes, name);
                        }
                    }
                    // Closing the stream is enough here. fsync() per extracted file made the
                    // 6k+ entry Steam package dramatically slower on Android flash storage.
                }
            }
        }

        ConsoleLogStore.flush();
        ConsoleLogStore.ok("EXTRACCIÓN COMPLETA · " + formatBytes(extracted));
    }

    private static boolean isUsableArchive(File archive) {
        if (archive == null || !archive.isFile() || archive.length() < 1024L * 1024L) return false;
        if (!hasSevenZipSignature(archive)) return false;
        try {
            ArchiveIndex index = indexArchive(archive);
            return index.containsSteamExe;
        }
        catch (Exception error) {
            ConsoleLogStore.warn("CACHE inválida: " + error.getMessage());
            return false;
        }
    }

    private static boolean hasSevenZipSignature(File file) {
        byte[] magic = {(byte)0x37, (byte)0x7A, (byte)0xBC, (byte)0xAF, (byte)0x27, (byte)0x1C};
        try (FileInputStream in = new FileInputStream(file)) {
            for (byte expected : magic) {
                int value = in.read();
                if (value < 0 || (byte)value != expected) return false;
            }
            return true;
        }
        catch (Exception ignored) {
            return false;
        }
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new BufferedInputStream(new FileInputStream(file), 1024 * 1024)) {
            byte[] buffer = new byte[1024 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) digest.update(buffer, 0, read);
        }
        StringBuilder out = new StringBuilder();
        for (byte b : digest.digest()) out.append(String.format(Locale.US, "%02x", b & 0xff));
        return out.toString();
    }

    private static String normalizeEntryName(String name) {
        if (name == null) return "";
        String value = name.replace('\\', '/');
        while (value.startsWith("/")) value = value.substring(1);
        while (value.startsWith("./")) value = value.substring(2);
        return value;
    }

    private static void publish(Phase phase, String detail, int progress, long current, long total, String currentFile) {
        State next = new State(phase, detail, Math.max(0, Math.min(100, progress)), current, total, currentFile);
        state = next;
        main.post(() -> {
            for (Listener listener : listeners) listener.onInstallerState(next);
        });
    }

    private static String formatBytes(long bytes) {
        if (bytes < 0) return "?";
        if (bytes >= 1024L * 1024L * 1024L) return String.format(Locale.US, "%.2f GB", bytes / 1073741824.0);
        if (bytes >= 1024L * 1024L) return String.format(Locale.US, "%.1f MB", bytes / 1048576.0);
        if (bytes >= 1024L) return String.format(Locale.US, "%.0f KB", bytes / 1024.0);
        return bytes + " B";
    }

    private static String formatRate(double bytesPerSecond) {
        if (bytesPerSecond >= 1024.0 * 1024.0) return String.format(Locale.US, "%.1f MB/s", bytesPerSecond / 1048576.0);
        if (bytesPerSecond >= 1024.0) return String.format(Locale.US, "%.0f KB/s", bytesPerSecond / 1024.0);
        return String.format(Locale.US, "%.0f B/s", bytesPerSecond);
    }

    private static final class ArchiveIndex {
        final int entries;
        final long uncompressedBytes;
        final boolean containsSteamExe;

        ArchiveIndex(int entries, long uncompressedBytes, boolean containsSteamExe) {
            this.entries = entries;
            this.uncompressedBytes = uncompressedBytes;
            this.containsSteamExe = containsSteamExe;
        }
    }
}
