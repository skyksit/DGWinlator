package com.winlator.bridge;

import com.winlator.core.FileUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Optional {@code dgplayer.ini} shipped at the root of a game archive.
 *
 * <p>It exists so per-game tuning travels with the game instead of living in DGPlayer's catalog: the
 * app's game database has no column for a Windows executable path, and adding one would mean a Room
 * migration for something only this console needs. Whoever packages the archive already knows which
 * .exe to run and which wrapper it needs, so that knowledge is recorded next to the files.
 *
 * <p>Format is one {@code key=value} per line; {@code #} starts a comment. Recognized keys mirror the
 * intent extras: {@code exe}, {@code args}, {@code screenSize}, {@code graphicsDriver},
 * {@code dxwrapper}, {@code box64Preset}, {@code envVars}, {@code forceFullscreen}.
 * Intent extras from the caller take precedence over anything found here.
 *
 * <p>{@code copy} is the exception: it may repeat, and it is manifest-only. Plenty of 90s Windows
 * titles keep their settings in {@code %WINDIR%} rather than beside the executable — the original
 * install would have dropped them there — so a package needs a way to say "this file belongs
 * outside my folder":
 * <pre>copy=Sizuku/Sizuku.ini -&gt; C:\windows\Sizuku.ini</pre>
 */
class GameManifest {
    static final String FILENAME = "dgplayer.ini";
    private static final String KEY_COPY = "copy";
    private static final String KEY_REG = "reg";

    private final Map<String, String> values = new HashMap<>();
    private final List<String[]> copies = new ArrayList<>();
    private final List<String> regFiles = new ArrayList<>();

    private GameManifest() {}

    static GameManifest read(File gameDir) {
        GameManifest manifest = new GameManifest();
        File file = new File(gameDir, FILENAME);
        if (!file.isFile()) return manifest;

        for (String line : FileUtils.readLines(file)) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("[")) continue;

            int index = line.indexOf('=');
            if (index <= 0) continue;

            String key = line.substring(0, index).trim();
            String value = line.substring(index+1).trim();
            if (key.isEmpty() || value.isEmpty()) continue;

            if (key.equals(KEY_COPY)) {
                int arrow = value.indexOf("->");
                if (arrow > 0) {
                    String from = value.substring(0, arrow).trim();
                    String to = value.substring(arrow+2).trim();
                    if (!from.isEmpty() && !to.isEmpty()) manifest.copies.add(new String[]{from, to});
                }
            }
            else if (key.equals(KEY_REG)) manifest.regFiles.add(value);
            else manifest.values.put(key, value);
        }
        return manifest;
    }

    /** Each entry is {sourceRelativeToGameDir, destinationDosPath}. */
    List<String[]> getCopies() {
        return copies;
    }

    /** {@code .reg} files, relative to the game directory, to merge into the prefix before launch. */
    List<String> getRegFiles() {
        return regFiles;
    }

    String get(String key) {
        return values.get(key);
    }

    boolean getBoolean(String key) {
        String value = values.get(key);
        return "1".equals(value) || "true".equalsIgnoreCase(value);
    }
}
