package com.winlator.console;

import java.io.File;
import java.nio.file.Files;
import java.util.Map;

public final class TranslationCachePolicyTest {
    private static void check(boolean ok, String reason) { if (!ok) throw new AssertionError(reason); }
    public static void main(String[] args) throws Exception {
        File parent = Files.createTempDirectory("translation-cache-test").toFile();
        File outside = Files.createTempDirectory("translation-cache-outside").toFile();
        File folder = new File(parent, "steam-translation");
        try {
            check(TranslationCachePolicy.environment(parent, false).get("BOX64_DYNACACHE").equals("0"), "Opt-in only");
            check(!folder.exists(), "Disabled does no cache IO");
            Map<String, String> enabled = TranslationCachePolicy.environment(parent, true);
            check(enabled.get("BOX64_DYNACACHE").equals("1") && folder.isDirectory(), "Private cache created");
            check(enabled.get("BOX64_DYNACACHE_FOLDER").equals(folder.getCanonicalPath()), "Canonical private path");
            check(enabled.get("BOX64_DYNACACHE_LIMIT").equals("128"), "Bounded disk budget");
            File kept = new File(folder, "keep.box64"); Files.write(kept.toPath(), new byte[]{1,2,3});
            TranslationCachePolicy.environment(parent, false);
            check(kept.length() == 3, "Turning off does not delete data"); kept.delete(); folder.delete();
            Files.write(folder.toPath(), new byte[]{7});
            check(TranslationCachePolicy.environment(parent, true).get("BOX64_DYNACACHE").equals("0"), "Failed directory falls back");
            folder.delete();
            Files.createSymbolicLink(folder.toPath(), outside.toPath());
            check(TranslationCachePolicy.environment(parent, true).get("BOX64_DYNACACHE").equals("0"), "Cache redirect rejected");
            check(outside.list().length == 0, "Never writes outside private cache");
            check(TranslationCachePolicy.environment(null, true).get("BOX64_DYNACACHE").equals("0"), "Missing cache root");
            System.out.println("PASS cache policy: opt-in, private path, 128 MiB disk budget, retained data, fallback, symlink rejection");
        } finally { folder.delete(); parent.delete(); outside.delete(); }
    }
}
