package com.winlator.bridge;

import android.util.Log;

import com.winlator.container.Container;
import com.winlator.core.FileUtils;
import com.winlator.core.WineRegistryEditor;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Applies a Windows {@code .reg} file from a game package to the container's wineprefix.
 *
 * <p>Games converted out of the DOSP95 disk images arrive as a bare directory tree, which loses
 * everything the original installer wrote to the registry. Some of them refuse to start without it —
 * Sizuku's bundled {@code _INMM.DLL} audio shim, for instance, reports "no information in the
 * registry" and quits. A file copy cannot fix that, so a package needs to be able to ship the keys.
 *
 * <p>The format is the standard {@code .reg} interchange format so a packager can export the keys
 * from a real install rather than hand-writing them. Only the subset those exports actually use is
 * parsed: section headers, string values, dwords and hex blobs.
 *
 * <p>Writing happens directly against the prefix's {@code user.reg}/{@code system.reg} through
 * {@link WineRegistryEditor}, so it must run while Wine is stopped — i.e. before the bridge hands
 * off to {@code XServerDisplayActivity}.
 */
abstract class RegistryImporter {
    private static final String TAG = "DGPlayerBridge";

    private static final String HKCU = "HKEY_CURRENT_USER\\";
    private static final String HKLM = "HKEY_LOCAL_MACHINE\\";

    private static class Entry {
        final String key;
        final String name;
        final String value;

        Entry(String key, String name, String value) {
            this.key = key;
            this.name = name;
            this.value = value;
        }
    }

    /** Applies {@code regFile}; returns false if nothing could be applied. */
    static boolean apply(Container container, File regFile) {
        if (!regFile.isFile()) {
            Log.w(TAG, "reg file missing: "+regFile);
            return false;
        }

        List<Entry> userEntries = new ArrayList<>();
        List<Entry> systemEntries = new ArrayList<>();
        String currentKey = null;
        List<Entry> currentTarget = null;

        for (String line : FileUtils.readLines(regFile)) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith(";") || line.startsWith("REGEDIT") || line.startsWith("Windows Registry")) continue;

            if (line.startsWith("[")) {
                int end = line.indexOf(']');
                if (end <= 1) continue;
                String section = line.substring(1, end).trim();

                if (section.regionMatches(true, 0, HKCU, 0, HKCU.length())) {
                    currentKey = section.substring(HKCU.length());
                    currentTarget = userEntries;
                }
                else if (section.regionMatches(true, 0, HKLM, 0, HKLM.length())) {
                    currentKey = section.substring(HKLM.length());
                    currentTarget = systemEntries;
                }
                else {
                    // HKCR/HKU and friends have no straightforward hive file here; skip loudly
                    // rather than silently dropping keys a package thought it had installed.
                    Log.w(TAG, "unsupported registry hive, skipping: "+section);
                    currentKey = null;
                    currentTarget = null;
                }
                continue;
            }

            if (currentKey == null || !line.startsWith("\"")) continue;

            int nameEnd = line.indexOf('"', 1);
            int equals = line.indexOf('=', nameEnd);
            if (nameEnd <= 0 || equals < 0) continue;

            currentTarget.add(new Entry(currentKey, line.substring(1, nameEnd), line.substring(equals+1).trim()));
        }

        boolean applied = write(new File(container.getRootDir(), ".wine/user.reg"), userEntries);
        applied |= write(new File(container.getRootDir(), ".wine/system.reg"), systemEntries);
        return applied;
    }

    private static boolean write(File hive, List<Entry> entries) {
        if (entries.isEmpty()) return false;
        if (!hive.isFile()) {
            Log.w(TAG, "hive missing: "+hive);
            return false;
        }

        try (WineRegistryEditor editor = new WineRegistryEditor(hive)) {
            editor.setCreateKeyIfNotExist(true);
            for (Entry entry : entries) {
                if (entry.value.startsWith("dword:")) {
                    try {
                        editor.setDwordValue(entry.key, entry.name, (int)Long.parseLong(entry.value.substring(6).trim(), 16));
                    }
                    catch (NumberFormatException e) {
                        Log.w(TAG, "bad dword for "+entry.name+": "+entry.value);
                    }
                }
                else if (entry.value.startsWith("hex:")) {
                    editor.setHexValue(entry.key, entry.name, entry.value.substring(4).trim());
                }
                else if (entry.value.startsWith("\"")) {
                    int end = entry.value.lastIndexOf('"');
                    String raw = end > 0 ? entry.value.substring(1, end) : "";
                    // .reg escapes backslashes; the registry wants the single-backslash form.
                    editor.setStringValue(entry.key, entry.name, raw.replace("\\\\", "\\").replace("\\\"", "\""));
                }
                else {
                    Log.w(TAG, "unsupported value type for "+entry.name+": "+entry.value);
                }
            }
        }
        Log.i(TAG, "applied "+entries.size()+" registry values to "+hive.getName());
        return true;
    }
}
