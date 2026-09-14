package com.winlator.bridge;

import android.content.Context;
import android.util.Log;

import com.winlator.core.FileUtils;
import com.winlator.inputcontrols.ControlsProfile;
import com.winlator.inputcontrols.InputControlsManager;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;

/**
 * The single upsert rule for Input Controls profiles that arrive over the DGPlayer bridge.
 *
 * <p>Both bridge entry points go through here — {@link GameLaunchActivity} when a game launches with
 * a generated layout, and {@link ControlsEditActivity} when DGPlayer opens the on-screen editor — so
 * a game can never end up with two profiles that differ only by which door they came in through.
 *
 * <p>The rule is <em>match by name, overwrite in place, keep the id</em>. Importing blindly would
 * pile up a fresh copy on every launch ({@link InputControlsManager#importProfile} always assigns a
 * new id), while reusing an existing profile untouched would pin the layout forever — and the whole
 * point is that editing it takes effect next time.
 */
final class BridgeControlsProfiles {
    private static final String TAG = "DGPlayerBridge";

    private BridgeControlsProfiles() {}

    /**
     * Writes {@code data} into the profile that already carries its {@code name}, or imports it as a
     * new one. Mutates {@code data}: on a name match its {@code id} is rewritten to the existing one.
     *
     * @param source human-readable origin, for logs only
     * @return the profile id to hand to {@code XServerDisplayActivity}/{@code ControlsEditorActivity},
     *         or 0 when the profile could not be stored
     */
    static int upsert(Context context, JSONObject data, String source) {
        try {
            String name = data.getString("name");

            InputControlsManager manager = new InputControlsManager(context);
            // getProfiles() has to run before importProfile(): the manager loads its list lazily and
            // importProfile() dereferences that list without checking it was loaded.
            for (ControlsProfile profile : manager.getProfiles()) {
                if (!profile.getName().equals(name)) continue;

                File file = ControlsProfile.getProfileFile(context, profile.id);
                data.put("id", profile.id);
                String updated = data.toString();
                if (file.isFile() && updated.equals(FileUtils.readString(file))) {
                    Log.i(TAG, "controls profile \""+name+"\" unchanged, reusing id "+profile.id);
                }
                else {
                    FileUtils.writeString(file, updated);
                    Log.i(TAG, "updated controls profile \""+name+"\" (id "+profile.id+", from "+source+")");
                }
                return profile.id;
            }

            ControlsProfile imported = manager.importProfile(data);
            if (imported == null) {
                Log.w(TAG, "failed to import controls profile: "+source);
                return 0;
            }
            Log.i(TAG, "imported controls profile \""+name+"\" as id "+imported.id+" (from "+source+")");
            return imported.id;
        }
        catch (JSONException e) {
            Log.w(TAG, "bad controls profile "+source, e);
            return 0;
        }
    }
}
