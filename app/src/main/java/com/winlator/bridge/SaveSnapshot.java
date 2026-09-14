package com.winlator.bridge;

import android.util.Log;

import com.winlator.container.Container;
import com.winlator.core.FileUtils;
import com.winlator.core.WineUtils;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.Stack;

/**
 * Decides which files under the container count as "this game's saves", and stats them.
 *
 * <p>Windows games have no common save location: some write beside the executable, some into the
 * user profile, some into {@code C:\windows}. Rather than make every package declare a path, the
 * bridge takes a before/after picture and keeps whatever changed. Detection is by
 * {@code (size, mtime)} — hashing a whole game folder on every launch would cost far more than the
 * stat walk, and tracked files are exported by content anyway.
 */
abstract class SaveSnapshot {
    private static final String TAG = SaveSync.TAG;

    private static final String USERS_XUSER = "users/xuser/";
    private static final String PROGRAM_DATA = "ProgramData";
    private static final String WINDOWS = "windows";

    /**
     * Paths never worth carrying between devices. Caches and logs would dominate the archive, and
     * the marker/label files are rewritten by the bridge itself on every launch — leaving those in
     * would make every session look "changed".
     */
    private static final String[] EXCLUDED_SUFFIXES = {
        ".log", ".dxvk-cache", ".vkd3d-cache", ".tmp", ".dmp"
    };
    private static final Set<String> EXCLUDED_NAMES = new HashSet<>(Arrays.asList(
        ".dgp_installed", ".windows-label", ".windows-serial",
        GameManifest.FILENAME.toLowerCase(Locale.ENGLISH), "desktop.ini", "thumbs.db"
    ));
    /** Wine/system owned and shared by every game in the container — never one game's save. */
    private static final Set<String> EXCLUDED_WINDOWS_NAMES = new HashSet<>(Arrays.asList(
        "win.ini", "system.ini"
    ));

    enum Scope { GAME_DIR, SHARED, ALL }

    static final class Roots {
        File driveC;
        File gameDir;
        String gameDirPrefix;
        /** Walked recursively. */
        final List<File> recursive = new ArrayList<>();
        /** Only the files directly inside are considered (no descent). */
        final List<File> topLevelOnly = new ArrayList<>();
        /** Relative paths the bridge rewrites on every launch. */
        final Set<String> excludedRel = new HashSet<>();
        /** Relative path prefixes excluded wholesale. */
        final List<String> excludedPrefixes = new ArrayList<>();
    }

    static Roots roots(Container container, String gameId, GameManifest manifest) {
        Roots roots = new Roots();
        roots.driveC = new File(container.getRootDir(), ".wine/drive_c");
        roots.gameDirPrefix = GameLaunchActivity.GAMES_DIR + "/" + gameId + "/";
        roots.gameDir = new File(roots.driveC, GameLaunchActivity.GAMES_DIR + "/" + gameId);

        roots.recursive.add(roots.gameDir);
        roots.recursive.add(container.getUserDir());
        roots.recursive.add(new File(roots.driveC, PROGRAM_DATA));
        // C:\windows ships 269 stock files; the top level is where a game's .ini lands (exactly what
        // the manifest copy= directive targets), so look there but do not descend.
        roots.topLevelOnly.add(new File(roots.driveC, WINDOWS));

        roots.excludedPrefixes.add(USERS_XUSER + "AppData/Local/Temp/");
        roots.excludedPrefixes.add(USERS_XUSER + "Temp/");
        roots.excludedPrefixes.add(USERS_XUSER + "AppData/Local/Microsoft/Windows/INetCache/");
        roots.excludedPrefixes.add(USERS_XUSER + "AppData/Roaming/Microsoft/Windows/Start Menu/");
        roots.excludedPrefixes.add(USERS_XUSER + "AppData/Roaming/Microsoft/Windows/Recent/");

        // copy= targets are rewritten from the package on every launch (GameLaunchActivity
        // .applyCopies), so tracking them would preserve the package default rather than the
        // player's edit. Resolve them the way applyCopies does so both agree on the path.
        if (manifest != null) {
            for (String[] copy : manifest.getCopies()) {
                String destination = WineUtils.dosToUnixPath(copy[1], container);
                if (destination == null || destination.isEmpty()) continue;
                String rel = rel(roots.driveC, new File(destination));
                // Destinations outside drive_c (E:, Z:) are not watched in the first place.
                if (rel != null) roots.excludedRel.add(rel);
            }
        }
        return roots;
    }

    /**
     * @param scope which roots to walk — the game folder and the shared roots have different
     *              baseline lifetimes, so they are snapshotted separately
     */
    static Map<String, long[]> snapshot(Roots roots, Scope scope) {
        Map<String, long[]> result = new LinkedHashMap<>();
        if (scope != Scope.SHARED) walkRecursive(roots, roots.gameDir, result);
        if (scope != Scope.GAME_DIR) {
            for (File dir : roots.recursive) {
                if (dir.equals(roots.gameDir)) continue;
                walkRecursive(roots, dir, result);
            }
            for (File dir : roots.topLevelOnly) walkTopLevel(roots, dir, result);
        }
        return result;
    }

    /** New paths, plus paths whose size or mtime moved. */
    static List<String> diff(Map<String, long[]> baseline, Map<String, long[]> current) {
        List<String> changed = new ArrayList<>();
        for (Map.Entry<String, long[]> entry : current.entrySet()) {
            long[] before = baseline.get(entry.getKey());
            if (before == null || !sameStat(before, entry.getValue())) changed.add(entry.getKey());
        }
        return changed;
    }

    static boolean sameStat(long[] a, long[] b) {
        if (a == null || b == null) return false;
        if (a.length < 2 || b.length < 2) return a.length == b.length;
        return a[0] == b[0] && a[1] == b[1];
    }

    /** @return the {@code drive_c}-relative path, or null when the file is outside it */
    static String rel(File driveC, File file) {
        try {
            String base = driveC.getCanonicalPath() + File.separator;
            String path = file.getCanonicalPath();
            if (!path.startsWith(base)) return null;
            return path.substring(base.length()).replace(File.separatorChar, '/');
        }
        catch (IOException e) {
            return null;
        }
    }

    static boolean isExcluded(Roots roots, String rel) {
        if (roots.excludedRel.contains(rel)) return true;
        for (String prefix : roots.excludedPrefixes) {
            if (rel.startsWith(prefix)) return true;
        }

        String name = rel.substring(rel.lastIndexOf('/') + 1).toLowerCase(Locale.ENGLISH);
        if (EXCLUDED_NAMES.contains(name)) return true;
        for (String suffix : EXCLUDED_SUFFIXES) {
            if (name.endsWith(suffix)) return true;
        }
        if (rel.startsWith(WINDOWS + "/") && rel.indexOf('/', WINDOWS.length() + 1) == -1
                && EXCLUDED_WINDOWS_NAMES.contains(name)) {
            return true;
        }
        return false;
    }

    private static void walkRecursive(Roots roots, File dir, Map<String, long[]> result) {
        if (dir == null || !dir.isDirectory()) return;

        Stack<File> pending = new Stack<>();
        pending.push(dir);
        while (!pending.isEmpty()) {
            File[] children = pending.pop().listFiles();
            if (children == null) continue;
            for (File child : children) {
                // drive_c has no symlinks today (only .wine/dosdevices does); skipping them anyway
                // keeps a future one from being archived or walked into a loop.
                if (FileUtils.isSymlink(child)) continue;
                if (child.isDirectory()) pending.push(child);
                else collect(roots, child, result);
            }
        }
    }

    private static void walkTopLevel(Roots roots, File dir, Map<String, long[]> result) {
        if (dir == null || !dir.isDirectory()) return;
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child.isFile() && !FileUtils.isSymlink(child)) collect(roots, child, result);
        }
    }

    private static void collect(Roots roots, File file, Map<String, long[]> result) {
        String rel = rel(roots.driveC, file);
        if (rel == null || isExcluded(roots, rel)) return;

        long length = file.length();
        if (length > SaveSync.MAX_FILE_BYTES) {
            Log.w(TAG, "skipping oversized file " + rel + " (" + length + " bytes)");
            return;
        }
        result.put(rel, new long[]{length, file.lastModified()});
    }
}
