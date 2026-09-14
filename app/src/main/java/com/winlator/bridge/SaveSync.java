package com.winlator.bridge;

import android.content.Context;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.util.Log;

import com.winlator.container.Container;

import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Keeps a Windows game's in-game saves alive outside this app.
 *
 * <p>Everything a game writes lands in this app's private storage: the game folder under
 * {@code drive_c/DGPlayer/<gameId>}, the Wine user profile, {@code C:\windows}. DGPlayer cannot read
 * any of it, and three ordinary events destroy it — repackaging the game (the bridge wipes the game
 * folder so old and new files cannot merge), deleting the container, and uninstalling this app.
 *
 * <p>So the saves are mirrored into an archive that lives in DGPlayer's own storage, reached through
 * a content URI DGPlayer grants with the launch intent ({@link #EXTRA_SAVE_URI}). From there they
 * ride along with DGPlayer's existing per-game and full cloud backup. The flow per launch is:
 *
 * <ol>
 *   <li>{@link #hasPendingExport} + {@link #exportOnExit} — rescue a previous session that never
 *       reached {@code exit()} (crash, force-stop), before a re-import can wipe the game folder</li>
 *   <li>{@link #onPayloadInstalled} — after an import, record what the package itself contains so
 *       those files are never mistaken for saves</li>
 *   <li>{@link #restoreIfNeeded} — pull the archive in when it is newer than what this device last
 *       restored, or when the game folder was just wiped</li>
 *   <li>{@link #beginSession} — photograph the shared roots so this game's writes can be told apart
 *       from what was already there</li>
 *   <li>{@link #exportOnExit} — at game exit, diff, accumulate, and write the archive back</li>
 * </ol>
 *
 * <p>Which files count as saves is decided by {@link SaveSnapshot}; the archive format and its
 * {@code stamp} by {@link SaveArchive}; the per-game bookkeeping by {@link SaveState}.
 *
 * <p>Nothing here may throw into a caller: a failed save sync must never take a game launch or a
 * game exit down with it.
 */
public abstract class SaveSync {
    static final String TAG = "DGPlayerBridge";

    /** Content URI of DGPlayer's {@code files/win/save/<fileName>.zip}, granted read + write. */
    public static final String EXTRA_SAVE_URI = "save_uri";
    /** Passed on to {@code XServerDisplayActivity} so both halves key the same state file. */
    public static final String EXTRA_GAME_ID = GameLaunchActivity.EXTRA_GAME_ID;

    static final int FORMAT_VERSION = 1;
    /** Stat value meaning "known to this game, currently absent" (a save the game deleted). */
    static final long[] MISSING = new long[0];

    /** A single save file this large is a disc image or a crash dump, not a save. */
    static final long MAX_FILE_BYTES = 64L << 20;
    /** DGPlayer drops anything over 80 MB from cloud backup; stay clear of that ceiling. */
    static final long MAX_TOTAL_BYTES = 64L << 20;

    /** Staging area for archives being read or written. Inside filesDir, never in the cache. */
    static File tempDir(Context context) {
        File dir = new File(SaveState.dirFor(context), "tmp");
        if (!dir.isDirectory()) dir.mkdirs();
        return dir;
    }

    static boolean hasPendingExport(Context context, String gameId) {
        if (context == null || gameId == null) return false;
        return SaveState.load(context, gameId).pendingExport;
    }

    /**
     * Records the freshly extracted package as the game folder's baseline.
     *
     * <p>Without this every file the package ships would read as "new" at the next exit and the
     * archive would swell to the size of the game. Called right after a successful import, when the
     * folder holds exactly the package and nothing else.
     */
    static void onPayloadInstalled(Context context, Container container, String gameId) {
        try {
            SaveState state = SaveState.load(context, gameId);
            SaveSnapshot.Roots roots = SaveSnapshot.roots(container, gameId, null);
            state.gameDirBaseline = SaveSnapshot.snapshot(roots, SaveSnapshot.Scope.GAME_DIR);
            state.containerId = container.id;
            state.save(context);
            Log.i(TAG, "save baseline after install: " + state.gameDirBaseline.size() + " files");
        }
        catch (Throwable t) {
            Log.e(TAG, "could not record the install baseline", t);
        }
    }

    /**
     * Restores the archive into the container when appropriate.
     *
     * <p>The decision is a stamp comparison, not a timestamp one: clocks and mtimes are unreliable
     * across a cloud round trip, while the stamp is a fresh id minted at each export and recorded
     * locally once applied.
     *
     * <ul>
     *   <li>different stamp (or nothing restored here yet) — restore everything. This is cloud
     *       restore, a manual import, a reinstalled app, a recreated container</li>
     *   <li>same stamp but the payload was just re-imported — restore the game folder only; the
     *       shared roots were never wiped and this device's copies of them are at least as new</li>
     *   <li>same stamp, no re-import — fill in only what is missing. The container's copies are at
     *       least as new so they are left alone, but a file that is simply gone (the container was
     *       cleared, a save was deleted) has the archive as its last copy</li>
     * </ul>
     *
     * @return true when files were written into the container
     */
    static boolean restoreIfNeeded(Context context, Container container, String gameId, Uri saveUri,
                                   GameManifest manifest, boolean payloadReinstalled) {
        if (context == null || container == null || gameId == null || saveUri == null) return false;

        try {
            long started = SystemClock.elapsedRealtime();
            SaveState state = SaveState.load(context, gameId);
            SaveSnapshot.Roots roots = SaveSnapshot.roots(container, gameId, manifest);

            SaveArchive.Archive archive = SaveArchive.open(context, saveUri, tempDir(context), gameId);
            if (archive == null) {
                Log.i(TAG, "save restore gameId=" + gameId + " -> skip (no usable archive)");
                return false;
            }

            try {
                boolean sameStamp = archive.stamp.equals(state.lastStamp);
                boolean gameDirOnly = sameStamp && payloadReinstalled;
                boolean onlyMissing = sameStamp && !payloadReinstalled;

                Map<String, long[]> restored = new LinkedHashMap<>();
                int written = SaveArchive.extract(archive, roots, gameDirOnly, onlyMissing, restored);

                String mode = gameDirOnly ? "gameDirOnly" : (onlyMissing ? "missingOnly" : "full");
                if (written == 0) {
                    Log.i(TAG, "save restore gameId=" + gameId + " stamp=" + archive.stamp
                            + " -> nothing to do (" + mode + ")");
                    return false;
                }

                state.tracked.putAll(restored);
                // Only extend an existing baseline. A null one means the game folder has never been
                // photographed, and seeding it with just these files would make the whole package
                // look like a save at the next export.
                if (state.gameDirBaseline != null) {
                    for (Map.Entry<String, long[]> entry : restored.entrySet()) {
                        if (entry.getKey().startsWith(roots.gameDirPrefix)) {
                            state.gameDirBaseline.put(entry.getKey(), entry.getValue());
                        }
                    }
                }
                state.lastStamp = archive.stamp;
                state.containerId = container.id;
                state.save(context);

                Log.i(TAG, String.format(Locale.ENGLISH,
                        "save restore gameId=%s zipStamp=%s reinstalled=%b -> %s entries=%d ms=%d",
                        gameId, archive.stamp, payloadReinstalled, mode, written,
                        SystemClock.elapsedRealtime() - started));
                return true;
            }
            finally {
                archive.close();
            }
        }
        catch (Throwable t) {
            Log.e(TAG, "save restore failed", t);
            return false;
        }
    }

    /**
     * Photographs the shared roots at launch.
     *
     * <p>Per session on purpose: the container is shared by the whole library, so a file another
     * game wrote between two sessions of this one would otherwise surface here as this game's save.
     * The game folder baseline is not reset here — it is persistent, so in-folder changes made by a
     * session that crashed are still caught at the next exit.
     */
    static void beginSession(Context context, Container container, String gameId, GameManifest manifest) {
        try {
            SaveState state = SaveState.load(context, gameId);
            SaveSnapshot.Roots roots = SaveSnapshot.roots(container, gameId, manifest);
            state.sharedBaseline = SaveSnapshot.snapshot(roots, SaveSnapshot.Scope.SHARED);
            state.containerId = container.id;
            state.pendingExport = true;
            state.save(context);
            Log.i(TAG, "save session gameId=" + gameId + " containerId=" + container.id
                    + " sharedBaseline=" + state.sharedBaseline.size() + " files tracked="
                    + state.tracked.size());
        }
        catch (Throwable t) {
            Log.e(TAG, "could not start the save session", t);
        }
    }

    /**
     * Diffs against the baselines, adds whatever changed to this game's tracked set, and writes the
     * archive back to DGPlayer.
     *
     * <p>Runs before {@code finish()} so Android cannot reclaim the process halfway through, and
     * swallows everything — an exit path must not be able to crash.
     */
    public static void exportOnExit(Context context, Container container, String gameId, Uri saveUri) {
        if (context == null || container == null || gameId == null || saveUri == null) return;
        try {
            export(context, container, gameId, saveUri);
        }
        catch (Throwable t) {
            Log.e(TAG, "save export failed", t);
        }
    }

    private static void export(Context context, Container container, String gameId, Uri saveUri) {
        long started = SystemClock.elapsedRealtime();
        SaveState state = SaveState.load(context, gameId);
        SaveSnapshot.Roots roots = SaveSnapshot.roots(container, gameId,
                GameManifest.read(gameDirOf(container, gameId)));

        Map<String, long[]> current = SaveSnapshot.snapshot(roots, SaveSnapshot.Scope.ALL);

        // Re-filter what is already tracked. Without this a path that a past build wrongly picked
        // up would be exported for the rest of the game's life, since tracking is cumulative and
        // never re-examined - widening the exclusion list has to clean up after itself.
        int untracked = 0;
        for (Iterator<String> it = state.tracked.keySet().iterator(); it.hasNext(); ) {
            if (SaveSnapshot.isExcluded(roots, it.next())) {
                it.remove();
                untracked++;
            }
        }
        if (untracked > 0) Log.i(TAG, "save export dropped " + untracked + " newly excluded path(s)");

        boolean gameDirBaselineKnown = state.gameDirBaseline != null;
        Map<String, long[]> baseline = new LinkedHashMap<>();
        if (gameDirBaselineKnown) baseline.putAll(state.gameDirBaseline);
        baseline.putAll(state.sharedBaseline);

        List<String> changed = new ArrayList<>();
        for (String rel : SaveSnapshot.diff(baseline, current)) {
            // Games imported before this feature existed have no install baseline. Claiming their
            // whole folder as "new" would upload the game itself, so skip the folder for one run and
            // rebuild the baseline below; only later changes are then picked up.
            if (!gameDirBaselineKnown && rel.startsWith(roots.gameDirPrefix)) continue;
            changed.add(rel);
        }
        if (!gameDirBaselineKnown) {
            Log.w(TAG, "no install baseline for " + gameId + ", rebuilding it and skipping the "
                    + "game folder diff this once");
        }

        int added = 0;
        for (String rel : changed) {
            if (state.tracked.put(rel, current.get(rel)) == null) added++;
        }

        int existing = 0;
        for (String rel : state.tracked.keySet()) {
            if (new File(roots.driveC, rel).isFile()) existing++;
        }
        if (existing == 0) {
            // Includes the case where the container was deleted: the archive DGPlayer already holds
            // is then the only copy of these saves, and must not be overwritten with an empty one.
            state.pendingExport = false;
            rebuildBaselines(state, roots, current);
            state.save(context);
            Log.i(TAG, "save export gameId=" + gameId + " -> nothing to save");
            return;
        }

        if (changed.isEmpty() && unchangedSinceLastExport(state, current)
                && archiveLooksPresent(context, saveUri)) {
            state.pendingExport = false;
            rebuildBaselines(state, roots, current);
            state.save(context);
            Log.i(TAG, "save export gameId=" + gameId + " -> skipped: unchanged");
            return;
        }

        long[] bytes = new long[1];
        String stamp = SaveArchive.write(context, saveUri, tempDir(context), gameId, container.id,
                new ArrayList<>(state.tracked.keySet()), roots, current, bytes);

        if (stamp == null) {
            // Leave pendingExport set so the next launch retries before anything can wipe the files.
            state.containerId = container.id;
            state.save(context);
            Log.e(TAG, "save export gameId=" + gameId + " -> failed, will retry on next launch");
            return;
        }

        int missing = 0;
        for (String rel : new ArrayList<>(state.tracked.keySet())) {
            long[] stat = current.get(rel);
            if (stat == null) {
                state.tracked.put(rel, MISSING);
                missing++;
            }
            else state.tracked.put(rel, stat);
        }

        state.lastStamp = stamp;
        state.lastExportAt = System.currentTimeMillis();
        state.pendingExport = false;
        state.containerId = container.id;
        rebuildBaselines(state, roots, current);
        state.save(context);

        Log.i(TAG, String.format(Locale.ENGLISH,
                "save export gameId=%s stamp=%s files=%d bytes=%d new=%d changed=%d missing=%d "
                        + "dropped=%d ms=%d",
                gameId, stamp, existing, bytes[0], added, changed.size(), missing, untracked,
                SystemClock.elapsedRealtime() - started));
    }

    private static File gameDirOf(Container container, String gameId) {
        return new File(container.getRootDir(),
                ".wine/drive_c/" + GameLaunchActivity.GAMES_DIR + "/" + gameId);
    }

    private static void rebuildBaselines(SaveState state, SaveSnapshot.Roots roots,
                                         Map<String, long[]> current) {
        Map<String, long[]> gameDir = new LinkedHashMap<>();
        Map<String, long[]> shared = new LinkedHashMap<>();
        for (Map.Entry<String, long[]> entry : current.entrySet()) {
            if (entry.getKey().startsWith(roots.gameDirPrefix)) gameDir.put(entry.getKey(), entry.getValue());
            else shared.put(entry.getKey(), entry.getValue());
        }
        state.gameDirBaseline = gameDir;
        state.sharedBaseline = shared;
    }

    /** True when every tracked file is exactly as the last export left it. */
    private static boolean unchangedSinceLastExport(SaveState state, Map<String, long[]> current) {
        for (Map.Entry<String, long[]> entry : state.tracked.entrySet()) {
            long[] before = entry.getValue();
            long[] now = current.get(entry.getKey());
            boolean beforeMissing = before == null || before.length < 2;
            if (now == null) {
                if (!beforeMissing) return false;
            }
            else if (beforeMissing || !SaveSnapshot.sameStat(before, now)) return false;
        }
        return true;
    }

    private static boolean archiveLooksPresent(Context context, Uri saveUri) {
        try (ParcelFileDescriptor pfd = context.getContentResolver().openFileDescriptor(saveUri, "r")) {
            return pfd != null && pfd.getStatSize() > 22;
        }
        catch (Exception e) {
            return false;
        }
    }
}
