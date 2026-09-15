package com.winlator.bridge;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.winlator.core.FileUtils;
import com.winlator.inputcontrols.ControlsProfile;
import com.winlator.inputcontrols.InputControlsManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Exported entry point that keeps DGPlayer's controls-layout library and this app's profile list in
 * step. One round trip does both directions.
 *
 * <p>DGPlayer owns the library — it lives in DGPlayer's files and rides its cloud backup, so it
 * survives a reinstall of this app. What lives here is a projection of it. There is no way for an
 * outside app to enumerate {@code files/profiles/} (it is app-private and there is no
 * ContentProvider), which is why this activity exists at all.
 *
 * <p>Contract, all via {@code startActivityForResult} (see {@link BridgeSecurity}):
 * <pre>
 *   in   controls_profiles_json : JSON array of .icp objects to store. Optional; omit to only read.
 *        prune_owned           : delete profiles whose name starts with "VPAD " that were not in
 *                                 this push. Profiles without that prefix are never touched.
 *   out  controls_profiles_json : every profile this app currently holds, each a complete .icp
 * </pre>
 *
 * <p>The name prefix is the only ownership marker that survives: {@link ControlsProfile#save()}
 * rewrites a profile from its known fields, so any custom key DGPlayer embedded is dropped the
 * moment the user saves in the editor. The prefix is what is left.
 *
 * <p>Draws nothing — it finishes inside {@code onCreate} under a translucent theme.
 */
public class ControlsSyncActivity extends AppCompatActivity {
    private static final String TAG = "DGPlayerBridge";

    // Must stay a literal mirroring AndroidManifest.xml (the manifest cannot reference BuildConfig).
    public static final String ACTION_SYNC_CONTROLS = "com.retrople.action.SYNC_CONTROLS";

    public static final String EXTRA_CONTROLS_PROFILES = "controls_profiles_json";
    public static final String EXTRA_PRUNE_OWNED = "prune_owned";

    /**
     * Only profiles named with this prefix may be pruned. See the class comment.
     *
     * <p>⚠ Must equal {@code WinLayoutLibrary.NAME_PREFIX} on the DGPlayer side. Change one without
     * the other and every profile under the old prefix becomes an orphan here: DGPlayer stops
     * claiming it and this sweep stops seeing it.
     */
    private static final String OWNER_PREFIX = "VPAD ";

    /**
     * Guardrail on the reply. A TransactionTooLargeException fires inside the binder, where the
     * caller cannot catch it. Measured: this app's whole 53-profile catalogue is ~127 KB, so the
     * cap is never reached in practice.
     */
    private static final int MAX_REPLY_BYTES = 512 * 1024;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (!BridgeSecurity.isCallerTrusted(this)) {
            finishWithError("Caller not authorized");
            return;
        }

        Set<String> pushed = applyIncoming(getIntent().getStringExtra(EXTRA_CONTROLS_PROFILES));
        if (getIntent().getBooleanExtra(EXTRA_PRUNE_OWNED, false)) pruneOwned(pushed);

        String reply = collectAll();
        if (reply == null) {
            finishWithError("Could not read the stored controls profiles");
            return;
        }

        Intent result = new Intent();
        result.putExtra(EXTRA_CONTROLS_PROFILES, reply);
        setResult(Activity.RESULT_OK, result);
        finish();
    }

    /**
     * Stores every profile in the incoming array, matched by name.
     *
     * @return the names that arrived, so {@link #pruneOwned} knows what to keep. Empty when nothing
     *         was sent, which deliberately makes a prune-only call a no-op rather than a wipe.
     */
    private Set<String> applyIncoming(String payload) {
        Set<String> names = new HashSet<>();
        if (payload == null || payload.isEmpty()) return names;

        JSONArray array;
        try {
            array = new JSONArray(payload);
        }
        catch (JSONException e) {
            Log.w(TAG, "bad controls profile array", e);
            return names;
        }

        int stored = 0;
        for (int i = 0; i < array.length(); i++) {
            JSONObject data = array.optJSONObject(i);
            if (data == null) continue;
            String name = data.optString("name");
            if (name.isEmpty()) continue;
            // upsert() mutates `data` (it rewrites "id"), so read the name before calling it.
            if (BridgeControlsProfiles.upsert(this, data, "sync") > 0) {
                names.add(name);
                stored++;
            }
        }
        Log.i(TAG, "sync stored "+stored+" of "+array.length()+" controls profiles");
        return names;
    }

    /**
     * Removes profiles DGPlayer owns that were not in this push — otherwise a layout the user
     * deleted over there lingers in this app's list forever and the two drift apart.
     *
     * <p>Only the {@value #OWNER_PREFIX} prefix is eligible, so the bundled profiles and anything
     * the user made in this app are safe. A prune with nothing pushed removes nothing.
     */
    private void pruneOwned(Set<String> keep) {
        if (keep.isEmpty()) return;

        InputControlsManager manager = new InputControlsManager(this);
        // Copy first: removeProfile() mutates the list this loop would be walking.
        List<ControlsProfile> stale = new ArrayList<>();
        for (ControlsProfile profile : manager.getProfiles()) {
            String name = profile.getName();
            if (name != null && name.startsWith(OWNER_PREFIX) && !keep.contains(name)) stale.add(profile);
        }

        for (ControlsProfile profile : stale) {
            manager.removeProfile(profile);
            Log.i(TAG, "sync pruned controls profile \""+profile.getName()+"\" (id "+profile.id+")");
        }
    }

    /**
     * Every stored profile, read straight off disk.
     *
     * <p>The manager's own objects are header-only ({@code loadProfile} stops after four fields and
     * never parses {@code elements}), so serializing them would hand back layouts with no buttons.
     */
    private String collectAll() {
        InputControlsManager manager = new InputControlsManager(this);
        JSONArray array = new JSONArray();
        int skipped = 0;

        for (ControlsProfile profile : manager.getProfiles()) {
            java.io.File file = ControlsProfile.getProfileFile(this, profile.id);
            if (!file.isFile()) continue;
            String json = FileUtils.readString(file);
            if (json == null || json.isEmpty()) continue;
            try {
                array.put(new JSONObject(json));
            }
            catch (JSONException e) {
                Log.w(TAG, "skipping unreadable profile "+profile.id, e);
                continue;
            }
            if (array.toString().length() > MAX_REPLY_BYTES) {
                // Drop what just overflowed and stop; a partial list beats a binder crash.
                array.remove(array.length() - 1);
                skipped = manager.getProfiles().size() - array.length();
                Log.w(TAG, "sync reply capped at "+array.length()+" profiles ("+skipped+" omitted)");
                break;
            }
        }

        Log.i(TAG, "sync returning "+array.length()+" controls profiles");
        return array.toString();
    }

    private void finishWithError(String message) {
        // Also logged: a Toast is useless to whatever is driving this activity, and every rejection
        // here otherwise looks identical from the outside (activity opens, closes, nothing happens).
        Log.e(TAG, message);
        setResult(Activity.RESULT_CANCELED);
        finish();
    }
}
