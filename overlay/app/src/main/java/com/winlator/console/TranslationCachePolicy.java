package com.winlator.console;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

/** Optional private disk cache; Box64 validates generated code, CPU, binary and dynarec settings. */
public final class TranslationCachePolicy {
    public static final String PREF = "droiddeck_translation_cache";
    public static final int LIMIT_MIB = 128;
    private TranslationCachePolicy() {}
    public static Map<String, String> environment(File parent, boolean enabled) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("BOX64_DYNACACHE", "0");
        if (!enabled || parent == null) return values;
        try {
            File folder = new File(parent.getCanonicalFile(), "steam-translation");
            // Do not follow a redirected cache directory outside Android's private cache root.
            if (!folder.getCanonicalFile().equals(folder)) return values;
            if ((!folder.isDirectory() && !folder.mkdirs()) || !folder.canWrite()) return values;
            values.put("BOX64_DYNACACHE", "1");
            values.put("BOX64_DYNACACHE_FOLDER", folder.getAbsolutePath());
            values.put("BOX64_DYNACACHE_LIMIT", "" + LIMIT_MIB);
            values.put("BOX64_DYNACACHE_COMPRESS", "1");
        } catch (Exception ignored) { /* Storage unavailable: preserve the normal uncached launch. */ }
        return values;
    }
}
