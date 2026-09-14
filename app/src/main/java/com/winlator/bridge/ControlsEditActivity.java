package com.winlator.bridge;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.winlator.ControlsEditorActivity;
import com.winlator.core.AppUtils;
import com.winlator.core.FileUtils;
import com.winlator.inputcontrols.ControlsProfile;

import org.json.JSONException;
import org.json.JSONObject;

/**
 * Exported entry point that lends Winlator's on-screen controls editor to DGPlayer.
 *
 * <p>DGPlayer can generate a layout ({@code WinControlsProfile}) but cannot show one: a WIN game runs
 * in this app's process, so DGPlayer's own pad editor is drawing a preview, not the real thing. This
 * activity hands the player the real editor — laid over the same canvas the game will use — and
 * returns whatever they made, so DGPlayer can store it and ship it back verbatim on the next launch.
 *
 * <p>Contract: send {@link GameLaunchActivity#EXTRA_CONTROLS_PROFILE} (a complete .icp JSON) with
 * {@code startActivityForResult}; on {@code RESULT_OK} the same extra comes back holding the edited
 * profile. {@code startActivity} will not do — {@link BridgeSecurity} authenticates the caller
 * through {@code getCallingPackage()}, which is null unless a result was requested.
 *
 * <p>⚠ {@code ControlsEditorActivity} has no save button and never calls {@code setResult}: every
 * change is written straight through to the profile file ({@code profile.save()} at ~13 call sites).
 * So the result is not in the result intent — it is the file, read back once the editor closes.
 */
public class ControlsEditActivity extends AppCompatActivity {
    private static final String TAG = "DGPlayerBridge";

    // Must stay a literal mirroring AndroidManifest.xml (the manifest cannot reference BuildConfig).
    public static final String ACTION_EDIT_CONTROLS = "com.retrople.action.EDIT_CONTROLS";

    private static final int REQUEST_EDIT = 1;
    private static final String STATE_PROFILE_ID = "profile_id";

    private int profileId;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        AppUtils.setActivityTheme(this);
        super.onCreate(savedInstanceState);

        if (savedInstanceState != null) {
            // Restored after the process was killed while the editor was in front. The editor is
            // still on the stack, so just remember which profile it is editing and wait for it.
            profileId = savedInstanceState.getInt(STATE_PROFILE_ID, 0);
            return;
        }

        if (!BridgeSecurity.isCallerTrusted(this)) {
            finishWithError("Caller not authorized");
            return;
        }

        String json = getIntent().getStringExtra(GameLaunchActivity.EXTRA_CONTROLS_PROFILE);
        if (json == null || json.isEmpty()) {
            finishWithError("Missing "+GameLaunchActivity.EXTRA_CONTROLS_PROFILE);
            return;
        }

        JSONObject data;
        try {
            data = new JSONObject(json);
        }
        catch (JSONException e) {
            finishWithError("Malformed controls profile");
            return;
        }

        profileId = BridgeControlsProfiles.upsert(this, data, "edit request");
        if (profileId <= 0) {
            finishWithError("Could not store the controls profile");
            return;
        }

        // ControlsEditorActivity calls profile.getName() on whatever loadProfile() returns, and that
        // is null when the file is absent -- a hard NPE rather than anything it could recover from.
        if (!ControlsProfile.getProfileFile(this, profileId).isFile()) {
            finishWithError("Controls profile file missing after import");
            return;
        }

        // No FLAG_ACTIVITY_NEW_TASK: a new task would never deliver onActivityResult back here.
        Intent intent = new Intent(this, ControlsEditorActivity.class);
        intent.putExtra("profile_id", profileId);
        startActivityForResult(intent, REQUEST_EDIT);
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_PROFILE_ID, profileId);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_EDIT) return;

        // resultCode is deliberately ignored -- see the class comment: the editor never sets one,
        // so RESULT_CANCELED is what a perfectly successful edit looks like from out here.
        String json = profileId > 0
                ? FileUtils.readString(ControlsProfile.getProfileFile(this, profileId))
                : null;
        if (json == null || json.isEmpty()) {
            finishWithError("Could not read the edited controls profile");
            return;
        }

        Log.i(TAG, "returning edited controls profile (id "+profileId+", "+json.length()+" chars)");
        Intent result = new Intent();
        result.putExtra(GameLaunchActivity.EXTRA_CONTROLS_PROFILE, json);
        setResult(Activity.RESULT_OK, result);
        finish();
    }

    private void finishWithError(String message) {
        // Also logged: a Toast is useless to whatever is driving this activity, and every rejection
        // here otherwise looks identical from the outside (activity opens, closes, nothing happens).
        Log.e(TAG, message);
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        setResult(Activity.RESULT_CANCELED);
        finish();
    }
}
