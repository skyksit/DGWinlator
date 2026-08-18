package com.winlator.bridge;

import android.util.Log;

import com.winlator.container.Container;
import com.winlator.core.FileUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
 * <p>Writing happens directly against the prefix's {@code user.reg}/{@code system.reg} as plain
 * text (see {@link #write}), so it must run while Wine is stopped — i.e. before the bridge hands
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

    /**
     * Rewrites the hive with our sections replaced wholesale: every existing section whose key
     * matches one of ours is dropped, then all of our sections are appended fresh at the end.
     *
     * <p>This deliberately avoids {@code WineRegistryEditor}: its native in-place value editing
     * corrupted user.reg when the same values were rewritten with different lengths on every
     * launch — Wine then logged "Malformed value name" and silently dropped the spliced entries,
     * which is exactly the kind of partial breakage a wholesale rewrite cannot produce. It also
     * makes the import self-healing: a section damaged by an older build is discarded and
     * re-emitted clean on the next launch.
     *
     * <p>The value text is carried over verbatim from the .reg file — the {@code "name"="value"} /
     * {@code dword:} / {@code hex:} syntax and escaping rules of .reg exports and Wine hive files
     * are the same. Multi-line hex continuations are not supported (the line-based parser above
     * never produces them).
     */
    private static boolean write(File hive, List<Entry> entries) {
        if (entries.isEmpty()) return false;
        if (!hive.isFile()) {
            Log.w(TAG, "hive missing: "+hive);
            return false;
        }

        // Group values by section, preserving the .reg file's section order.
        LinkedHashMap<String, List<Entry>> sections = new LinkedHashMap<>();
        for (Entry entry : entries) {
            List<Entry> list = sections.get(entry.key);
            if (list == null) {
                list = new ArrayList<>();
                sections.put(entry.key, list);
            }
            list.add(entry);
        }

        StringBuilder output = new StringBuilder(1 << 18);
        boolean skipping = false;
        for (String line : FileUtils.readLines(hive)) {
            if (line.startsWith("[")) {
                int end = line.indexOf(']');
                // Hive section headers escape backslashes; compare in the single-backslash form.
                String key = end > 1 ? line.substring(1, end).replace("\\\\", "\\") : null;
                skipping = key != null && containsKeyIgnoreCase(sections, key);
            }
            if (!skipping) output.append(line).append('\n');
        }

        // Same timestamp format WineRegistryEditor used, so Wine parses the headers as usual.
        long ticks1601To1970 = 86400L * (369 * 365 + 89) * 10000000;
        long currentTime = System.currentTimeMillis() + ticks1601To1970;
        for (Map.Entry<String, List<Entry>> section : sections.entrySet()) {
            output.append("\n[").append(section.getKey().replace("\\", "\\\\")).append("] ")
                  .append((currentTime - ticks1601To1970) / 1000).append('\n');
            output.append(String.format(Locale.ENGLISH, "#time=%x%08x%n", currentTime >> 32, (int)currentTime));
            for (Entry entry : section.getValue()) {
                if (!entry.value.startsWith("\"") && !entry.value.startsWith("dword:") && !entry.value.startsWith("hex:")) {
                    Log.w(TAG, "unsupported value type for "+entry.name+": "+entry.value);
                    continue;
                }
                output.append('"').append(entry.name.replace("\\", "\\\\").replace("\"", "\\\""))
                      .append("\"=").append(entry.value).append('\n');
            }
        }

        // Never leave a half-written hive behind: build the replacement next to it, then swap.
        File tempFile = new File(hive.getParentFile(), hive.getName()+".dgptmp");
        FileUtils.writeString(tempFile, output.toString());
        if (tempFile.length() == 0 || !tempFile.renameTo(hive)) {
            tempFile.delete();
            Log.w(TAG, "failed to rewrite "+hive.getName());
            return false;
        }

        Log.i(TAG, "applied "+entries.size()+" registry values to "+hive.getName());
        return true;
    }

    private static boolean containsKeyIgnoreCase(LinkedHashMap<String, List<Entry>> sections, String key) {
        for (String candidate : sections.keySet()) {
            if (candidate.equalsIgnoreCase(key)) return true;
        }
        return false;
    }
}
