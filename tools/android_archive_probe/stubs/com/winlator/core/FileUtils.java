package com.winlator.core;
/** Probe-only dependency used by upstream ProcessHelper.getProcessName. */
public final class FileUtils { public static String getName(String path) { return new java.io.File(path).getName(); } }
