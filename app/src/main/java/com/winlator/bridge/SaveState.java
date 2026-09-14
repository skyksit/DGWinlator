package com.winlator.bridge;

import android.content.Context;
import android.util.Log;

import com.winlator.core.FileUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-game bookkeeping for {@link SaveSync}, stored as JSON at
 * {@code filesDir/dgplayer_saves/<gameId>.json}.
 *
 * <p>It deliberately lives <em>outside</em> the rootfs. Anything under the container is fair game for
 * {@code PayloadInstaller.deleteRecursively} on a re-import and disappears entirely when the user
 * deletes the container, which is exactly when this state is needed most. It is also invisible to the
 * guest, which sees the rootfs root as {@code Z:}.
 *
 * <p>Path keys are relative to {@code .wine/drive_c}, {@code /}-separated, with no leading slash —
 * the same strings used as zip entry names, so a state file and an archive can be compared directly.
 * Each value is {@code [size, mtime]}, or an empty array for "known to this game but currently
 * missing" (a save the game itself deleted).
 */
class SaveState {
    private static final String TAG = SaveSync.TAG;

    private static final String KEY_FORMAT_VERSION = "formatVersion";
    private static final String KEY_GAME_ID = "gameId";
    private static final String KEY_CONTAINER_ID = "containerId";
    private static final String KEY_LAST_STAMP = "lastStamp";
    private static final String KEY_LAST_EXPORT_AT = "lastExportAt";
    private static final String KEY_PENDING_EXPORT = "pendingExport";
    private static final String KEY_TRACKED = "tracked";
    private static final String KEY_GAME_DIR_BASELINE = "gameDirBaseline";
    private static final String KEY_SHARED_BASELINE = "sharedBaseline";

    final String gameId;
    int containerId = -1;
    String lastStamp;
    long lastExportAt;
    boolean pendingExport;
    /** Every path this game is known to write. Union across sessions; the export contents. */
    final Map<String, long[]> tracked = new LinkedHashMap<>();
    /** Game folder contents as of the last install or export. Persistent across sessions. */
    Map<String, long[]> gameDirBaseline;
    /** Shared roots as of this session's start. Rewritten every launch (the container is shared). */
    Map<String, long[]> sharedBaseline = new LinkedHashMap<>();

    private SaveState(String gameId) {
        this.gameId = gameId;
    }

    static File dirFor(Context context) {
        return new File(context.getFilesDir(), "dgplayer_saves");
    }

    static File fileFor(Context context, String gameId) {
        return new File(dirFor(context), gameId+".json");
    }

    /** Never fails: a missing or unreadable state file yields a fresh, empty state. */
    static SaveState load(Context context, String gameId) {
        SaveState state = new SaveState(gameId);
        File file = fileFor(context, gameId);
        if (!file.isFile()) return state;

        try {
            JSONObject data = new JSONObject(FileUtils.readString(file));
            state.containerId = data.optInt(KEY_CONTAINER_ID, -1);
            state.lastStamp = data.has(KEY_LAST_STAMP) ? data.optString(KEY_LAST_STAMP, null) : null;
            state.lastExportAt = data.optLong(KEY_LAST_EXPORT_AT, 0L);
            state.pendingExport = data.optBoolean(KEY_PENDING_EXPORT, false);
            readMap(data.optJSONObject(KEY_TRACKED), state.tracked);
            if (data.has(KEY_GAME_DIR_BASELINE)) {
                state.gameDirBaseline = new LinkedHashMap<>();
                readMap(data.optJSONObject(KEY_GAME_DIR_BASELINE), state.gameDirBaseline);
            }
            readMap(data.optJSONObject(KEY_SHARED_BASELINE), state.sharedBaseline);
        }
        catch (Exception e) {
            // A corrupt state file must not block the game. Starting over costs one export cycle:
            // the game folder baseline is rebuilt and nothing is lost, only re-detected.
            Log.w(TAG, "unreadable save state for "+gameId+", starting fresh", e);
            return new SaveState(gameId);
        }
        return state;
    }

    /** Writes through a temp file so a kill mid-write cannot leave a truncated state behind. */
    boolean save(Context context) {
        File dir = dirFor(context);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            Log.e(TAG, "could not create "+dir);
            return false;
        }

        try {
            JSONObject data = new JSONObject();
            data.put(KEY_FORMAT_VERSION, SaveSync.FORMAT_VERSION);
            data.put(KEY_GAME_ID, gameId);
            data.put(KEY_CONTAINER_ID, containerId);
            if (lastStamp != null) data.put(KEY_LAST_STAMP, lastStamp);
            data.put(KEY_LAST_EXPORT_AT, lastExportAt);
            data.put(KEY_PENDING_EXPORT, pendingExport);
            data.put(KEY_TRACKED, writeMap(tracked));
            if (gameDirBaseline != null) data.put(KEY_GAME_DIR_BASELINE, writeMap(gameDirBaseline));
            data.put(KEY_SHARED_BASELINE, writeMap(sharedBaseline));

            File temp = FileUtils.createTempFile(dir, gameId);
            if (!FileUtils.writeString(temp, data.toString())) return false;
            File target = fileFor(context, gameId);
            target.delete();
            if (!temp.renameTo(target)) {
                temp.delete();
                return false;
            }
            return true;
        }
        catch (Exception e) {
            Log.e(TAG, "could not save state for "+gameId, e);
            return false;
        }
    }

    private static void readMap(JSONObject source, Map<String, long[]> target) {
        if (source == null) return;
        for (Iterator<String> it = source.keys(); it.hasNext(); ) {
            String key = it.next();
            JSONArray value = source.optJSONArray(key);
            if (value == null) continue;
            target.put(key, value.length() >= 2
                    ? new long[]{value.optLong(0), value.optLong(1)}
                    : SaveSync.MISSING);
        }
    }

    private static JSONObject writeMap(Map<String, long[]> source) throws Exception {
        JSONObject target = new JSONObject();
        for (Map.Entry<String, long[]> entry : source.entrySet()) {
            JSONArray value = new JSONArray();
            long[] stat = entry.getValue();
            if (stat != null && stat.length >= 2) {
                value.put(stat[0]);
                value.put(stat[1]);
            }
            target.put(entry.getKey(), value);
        }
        return target;
    }
}
