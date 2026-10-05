"""Wire optional DynaCache without changing engine, presets or cache integrity checks."""
from runtime_startup_fixes import replace_once


def apply_translation_cache_fixes(src):
    path = src / "app/src/main/java/com/winlator/xenvironment/components/GuestProgramLauncherComponent.java"
    text = path.read_text()
    text = replace_once(text, '        envVars.put("BOX64_DYNACACHE", "0");', """        java.util.Map<String, String> cache = com.winlator.console.TranslationCachePolicy.environment(
                context.getCacheDir(), preferences.getBoolean(com.winlator.console.TranslationCachePolicy.PREF, false));
        for (java.util.Map.Entry<String, String> value : cache.entrySet()) envVars.put(value.getKey(), value.getValue());
        com.winlator.console.ConsoleLogStore.info("TRADUCCIÓN · " + ("1".equals(cache.get("BOX64_DYNACACHE"))
                ? "caché experimental activa · objetivo de disco 128 MiB · RAM administrada por Box64"
                : "inicio sin caché persistente"));""")
    path.write_text(text)
