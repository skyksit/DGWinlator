package com.winlator.bridge;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.winlator.BuildConfig;
import com.winlator.MainActivity;
import com.winlator.R;
import com.winlator.XServerDisplayActivity;
import com.winlator.box64.Box64Preset;
import com.winlator.container.Container;
import com.winlator.container.ContainerManager;
import com.winlator.container.GraphicsDrivers;
import com.winlator.core.AppUtils;
import com.winlator.core.EnvVars;
import com.winlator.core.FileUtils;
import com.winlator.core.GPUHelper;
import com.winlator.inputcontrols.ControlsProfile;
import com.winlator.inputcontrols.InputControlsManager;
import com.winlator.core.PreloaderDialog;
import com.winlator.core.WineUtils;
import com.winlator.xenvironment.RootFS;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.concurrent.Executors;

/**
 * Exported entry point that lets DGPlayer (dsam3) launch a Windows game inside this container runtime.
 *
 * <p>Upstream Winlator has no external launch surface at all: {@code XServerDisplayActivity} is
 * {@code exported="false"} and {@code MainActivity} only accepts navigation extras. This activity is
 * the fork's contract — it resolves a shared container, imports the game payload once, applies the
 * per-game preset onto the container, and hands off to {@code XServerDisplayActivity}.
 *
 * <p>It deliberately does <em>not</em> write a {@code .desktop} shortcut. Winlator's {@code Shortcut}
 * parser reconstructs the executable path by string-slicing the {@code Exec=} line, which is fragile
 * to generate from the outside. Driving the container config directly is stable and
 * {@code XServerDisplayActivity} falls back to exactly those values when no shortcut is present.
 */
public class GameLaunchActivity extends AppCompatActivity {
    private static final String TAG = "DGPlayerBridge";

    // Must stay a literal mirroring AndroidManifest.xml (the manifest cannot reference BuildConfig).
    public static final String ACTION_PLAY_GAME = "com.dgplayer.action.PLAY_GAME";

    public static final String EXTRA_GAME_ID = "game_id";
    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_CONTENT_URI = "content_uri";
    public static final String EXTRA_EXE = "exe";
    public static final String EXTRA_EXE_ARGS = "exe_args";
    public static final String EXTRA_SCREEN_SIZE = "screen_size";
    public static final String EXTRA_GRAPHICS_DRIVER = "graphics_driver";
    public static final String EXTRA_DXWRAPPER = "dxwrapper";
    public static final String EXTRA_DXWRAPPER_CONFIG = "dxwrapper_config";
    public static final String EXTRA_BOX64_PRESET = "box64_preset";
    public static final String EXTRA_ENV_VARS = "env_vars";
    public static final String EXTRA_FORCE_FULLSCREEN = "force_fullscreen";

    /** Directory inside the container's C: drive that holds every game imported from DGPlayer. */
    public static final String GAMES_DIR = "DGPlayer";

    private static final String PREFS_NAME = "dgplayer_bridge";
    private static final String PREF_CONTAINER_ID = "container_id";
    private static final String CONTAINER_NAME = "DGPlayer";

    private final PreloaderDialog preloaderDialog = new PreloaderDialog(this);
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        AppUtils.setActivityTheme(this);
        super.onCreate(savedInstanceState);

        if (!isCallerTrusted()) {
            finishWithError("Caller not authorized");
            return;
        }

        final String gameId = sanitizeGameId(getIntent().getStringExtra(EXTRA_GAME_ID));
        if (gameId == null) {
            finishWithError("Missing or invalid game_id");
            return;
        }

        if (!RootFS.find(this).isValid()) {
            // First run of the fork itself. Installing the rootfs is MainActivity's job; sending the
            // user there is better than duplicating that flow inside the bridge.
            Toast.makeText(this, R.string.dgp_setup_required, Toast.LENGTH_LONG).show();
            startActivity(new Intent(this, MainActivity.class));
            finish();
            return;
        }

        preloaderDialog.show(R.string.starting_up);
        Executors.newSingleThreadExecutor().execute(() -> prepareAndLaunch(gameId));
    }

    /**
     * Maps the caller's game id onto a directory name that is safe both for the host filesystem and
     * for the DOS path it turns into.
     *
     * <p>Path separators would let the payload escape the container, and a space is just as bad in
     * practice: {@code XServerDisplayActivity.getWineStartCommand} emits the directory as an
     * unquoted {@code /dir <path>} argument, so a space there silently truncates the working
     * directory. Everything outside a conservative set collapses to an underscore, which is stable
     * across launches so the same game keeps resolving to the same directory.
     *
     * @return the sanitized id, or null if the input was missing or had nothing usable in it
     */
    private static String sanitizeGameId(String gameId) {
        if (gameId == null) return null;

        StringBuilder sanitized = new StringBuilder(gameId.length());
        for (int i = 0; i < gameId.length(); i++) {
            char c = gameId.charAt(i);
            boolean safe = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_';
            sanitized.append(safe ? c : '_');
        }

        // An id of only underscores (or "..", which collapses to "__") carries no identity.
        String result = sanitized.toString();
        return result.replace("_", "").isEmpty() ? null : result;
    }

    /**
     * Rejects callers that are not signed with our certificate. A null calling package means the
     * caller used {@code startActivity} rather than {@code startActivityForResult} (this is also what
     * {@code adb shell am start} looks like), which is only tolerated in debug builds so the intent
     * contract stays testable from a shell.
     */
    private boolean isCallerTrusted() {
        String callingPackage = getCallingPackage();
        if (callingPackage == null) return BuildConfig.DEBUG;
        return getPackageManager().checkSignatures(getPackageName(), callingPackage)
                == PackageManager.SIGNATURE_MATCH;
    }

    private void prepareAndLaunch(String gameId) {
        ContainerManager containerManager = new ContainerManager(this);
        Container container = obtainContainer(containerManager);
        if (container == null) {
            finishWithErrorOnUiThread("Could not create container");
            return;
        }

        File gameDir = new File(container.getRootDir(), ".wine/drive_c/"+GAMES_DIR+"/"+gameId);
        Uri contentUri = getIntent().getParcelableExtra(EXTRA_CONTENT_URI);

        if (!PayloadInstaller.isInstalled(gameDir)) {
            if (contentUri == null) {
                finishWithErrorOnUiThread("Game not installed and no content_uri supplied");
                return;
            }

            preloaderDialog.showOnUiThread(R.string.dgp_installing_game);
            // A half-extracted directory would look installed on the next run; start from scratch.
            PayloadInstaller.deleteRecursively(gameDir);
            if (!PayloadInstaller.install(this, contentUri, gameDir)) {
                PayloadInstaller.deleteRecursively(gameDir);
                finishWithErrorOnUiThread("Failed to import game payload");
                return;
            }
        }

        // Settings ship inside the archive; anything the caller sent explicitly overrides them.
        GameManifest manifest = GameManifest.read(gameDir);

        String exe = resolve(EXTRA_EXE, manifest.get("exe"));
        if (exe == null) {
            finishWithErrorOnUiThread("No executable: pass the 'exe' extra or ship "+GameManifest.FILENAME);
            return;
        }

        File exeFile = new File(gameDir, exe);
        if (!exeFile.isFile()) {
            finishWithErrorOnUiThread("Executable not found: "+exe);
            return;
        }

        applyPreset(container, manifest, gameDir);
        applyCopies(container, manifest, gameDir);

        // Everything below edits the prefix's registry hives as plain files, so it has to happen
        // while Wine is stopped — i.e. before XServerDisplayActivity starts.
        String locale = localeOf(resolve(EXTRA_ENV_VARS, manifest.get("envVars")));
        if (locale != null) CjkFontSubstitutes.apply(container, locale);

        for (String regFile : manifest.getRegFiles()) {
            RegistryImporter.apply(container, new File(gameDir, regFile.replace('\\', '/')));
        }

        int controlsProfileId = importControlsProfile(manifest, gameDir);

        // XServerDisplayActivity splits arguments off the executable name when the file name contains
        // a space after its extension (see getWineStartCommand), so appending them here is enough.
        String execPath = exeFile.getAbsolutePath();
        String execArgs = resolve(EXTRA_EXE_ARGS, manifest.get("args"));
        if (execArgs != null) execPath += " "+execArgs;

        boolean forceFullscreen = getIntent().hasExtra(EXTRA_FORCE_FULLSCREEN)
                ? getIntent().getBooleanExtra(EXTRA_FORCE_FULLSCREEN, false)
                : manifest.getBoolean("forceFullscreen");

        Intent intent = new Intent(this, XServerDisplayActivity.class);
        intent.putExtra("container_id", container.id);
        intent.putExtra("exec_path", execPath);
        // Tells XServerDisplayActivity.exit() to finish instead of restarting into MainActivity.
        intent.putExtra("from_bridge", true);
        if (controlsProfileId > 0) intent.putExtra("controls_profile", controlsProfileId);
        intent.putExtra("force_fullscreen", forceFullscreen);

        handler.post(() -> {
            preloaderDialog.close();
            startActivity(intent);
            finish();
        });
    }

    /**
     * Returns the single container shared by every DGPlayer game, creating it on first use.
     * One container per game would multiply a 1.5-3 GB wineprefix by the size of the library; games
     * are isolated by directory instead, and per-game settings ride on the container config.
     */
    private Container obtainContainer(ContainerManager containerManager) {
        SharedPreferences preferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        int containerId = preferences.getInt(PREF_CONTAINER_ID, -1);

        if (containerId != -1) {
            Container container = containerManager.getContainerById(containerId);
            if (container != null) return container;
            // The user deleted it from the Containers screen; fall through and make a new one.
        }

        Container container = createContainerSync(containerManager);
        if (container != null) {
            preferences.edit().putInt(PREF_CONTAINER_ID, container.id).apply();
        }
        return container;
    }

    /**
     * ContainerManager only exposes an async creator that posts its callback to a Handler, so this
     * bridges it back to the calling (background) thread.
     */
    private Container createContainerSync(ContainerManager containerManager) {
        final Container[] result = new Container[1];
        final Object lock = new Object();
        final boolean[] done = {false};

        try {
            JSONObject data = new JSONObject();
            data.put("name", CONTAINER_NAME);
            // Every other property is left out on purpose: Container.loadData only overwrites keys
            // that are present, so omitted ones keep their upstream defaults.

            handler.post(() -> containerManager.createContainerAsync(data, (container) -> {
                synchronized (lock) {
                    result[0] = container;
                    done[0] = true;
                    lock.notifyAll();
                }
            }));
        }
        catch (JSONException e) {
            return null;
        }

        synchronized (lock) {
            long deadline = System.currentTimeMillis() + 300000;
            while (!done[0]) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) return null;
                try {
                    lock.wait(remaining);
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        }
        return result[0];
    }

    /**
     * Imports the on-screen controls profile (.icp) a package ships via the {@code controlsProfile}
     * manifest key, and returns its profile id (0 if none).
     *
     * <p>dsam3's own VPAD overlay cannot reach a game running in this app, so touch controls have to
     * come from Winlator's InputControls system instead — this is the hook that lets a game package
     * carry its own layout. The profile is deduplicated by name: importing on every launch would
     * otherwise pile up copies (and {@code InputControlsManager.importProfile} assigns a fresh id
     * each call), so an existing profile with the same name is reused as-is. That also means a user's
     * in-game edits to the profile survive relaunches.
     */
    private int importControlsProfile(GameManifest manifest, File gameDir) {
        String relativePath = manifest.get("controlsProfile");
        if (relativePath == null || relativePath.isEmpty()) return 0;

        File icpFile = new File(gameDir, relativePath.replace('\\', '/'));
        if (!icpFile.isFile()) {
            Log.w(TAG, "controls profile missing from package: "+relativePath);
            return 0;
        }

        try {
            JSONObject data = new JSONObject(FileUtils.readString(icpFile));
            String name = data.getString("name");

            InputControlsManager manager = new InputControlsManager(this);
            for (ControlsProfile profile : manager.getProfiles()) {
                if (profile.getName().equals(name)) return profile.id;
            }

            ControlsProfile imported = manager.importProfile(data);
            if (imported == null) {
                Log.w(TAG, "failed to import controls profile: "+relativePath);
                return 0;
            }
            Log.i(TAG, "imported controls profile \""+name+"\" as id "+imported.id);
            return imported.id;
        }
        catch (JSONException e) {
            Log.w(TAG, "bad controls profile "+relativePath, e);
            return 0;
        }
    }

    /**
     * Copies files the package wants placed outside its own folder — typically settings the original
     * installer would have written into {@code C:\windows}.
     */
    private void applyCopies(Container container, GameManifest manifest, File gameDir) {
        for (String[] copy : manifest.getCopies()) {
            File source = new File(gameDir, copy[0].replace('\\', '/'));
            String destination = WineUtils.dosToUnixPath(copy[1], container);

            if (!source.isFile() || destination.isEmpty()) {
                Log.w(TAG, "skipping copy, unusable: "+copy[0]+" -> "+copy[1]);
                continue;
            }
            // Guard the same way the payload extraction does: a copy target is attacker-controlled
            // text, and dosToUnixPath happily resolves anything the drive table can reach.
            File destinationFile = new File(destination);
            if (!FileUtils.copy(source, destinationFile)) {
                Log.w(TAG, "copy failed: "+source+" -> "+destinationFile);
                continue;
            }
            FileUtils.chmod(destinationFile, 0771);
        }
    }

    /**
     * Writes the per-game preset onto the shared container.
     *
     * <p>Every managed field is written on every launch — anything the package leaves unset is reset
     * to the Winlator default rather than inherited. One container is shared by the whole library, so
     * "leave it alone if unspecified" means the previous game's settings silently apply to the next
     * one. That bit once already: a package that set {@code dxwrapper=cnc-ddraw} left the field set
     * for the following game, and because {@code DXWrappers.parseConfigs} only honours a config when
     * {@code dxwrapper} is dxvk or wined3d, that game's {@code ddrawWrapper} was quietly discarded.
     */
    private void applyPreset(Container container, GameManifest manifest, File gameDir) {
        boolean changed = false;

        // Point D: at this game's folder. DOSP95 shipped these titles as disk images that DOSBox
        // mounted as D:, so anything converted from one still refers to itself as D:\<Game>.
        // Safe against the fragile drives parser (it splits on ':') because the container path has
        // no colon in it; E: keeps its default so the app storage drive stays reachable.
        String drives = "D:"+gameDir.getAbsolutePath()+"E:"+AppUtils.INTERNAL_STORAGE;
        if (!drives.equals(container.getDrives())) {
            container.setDrives(drives);
            changed = true;
        }

        String screenSize = or(resolve(EXTRA_SCREEN_SIZE, manifest.get("screenSize")), Container.DEFAULT_SCREEN_SIZE);
        if (!screenSize.equals(container.getScreenSize())) {
            container.setScreenSize(screenSize);
            changed = true;
        }

        // Vulkan follows upstream's device detection (Adreno -> turnip, else vortek). The GL half
        // deliberately deviates from upstream's gladio default: on Adreno 830 / Android 16 gladio
        // maps windows but presents nothing — every GL-backed game (all DirectDraw wrappers included)
        // came up as a black screen with working audio, while the same game rendered the moment the
        // GL driver was switched to zink (GL-over-Vulkan). Verified 2026-08-17, 환세취호전/HWANSE.EXE.
        String defaultDriver = (GPUHelper.getAdrenoModelId(this) > 0
                ? GraphicsDrivers.TURNIP : GraphicsDrivers.DEFAULT_VULKAN_DRIVER)
                +","+GraphicsDrivers.ZINK;
        String graphicsDriver = or(resolve(EXTRA_GRAPHICS_DRIVER, manifest.get("graphicsDriver")), defaultDriver);
        if (!graphicsDriver.equals(container.getGraphicsDriver())) {
            container.setGraphicsDriver(graphicsDriver);
            changed = true;
        }

        // The Direct3D wrapper only. The DirectDraw one is a key inside dxwrapperConfig below —
        // and putting anything but dxvk/wined3d here makes DXWrappers.parseConfigs drop that config
        // on the floor, so never let a package's stray value survive into the next launch.
        String dxwrapper = or(resolve(EXTRA_DXWRAPPER, manifest.get("dxwrapper")), Container.DEFAULT_DXWRAPPER);
        if (!dxwrapper.equals(container.getDXWrapper())) {
            container.setDXWrapper(dxwrapper);
            changed = true;
        }

        // Comma-separated k=v. Holds "ddrawWrapper" (default wined3d), which is what a 90s
        // DirectDraw game actually renders through — wined3d needs GL, so on a Vulkan-only
        // container it draws nothing and the game looks like a black screen with sound.
        String dxwrapperConfig = or(resolve(EXTRA_DXWRAPPER_CONFIG, manifest.get("dxwrapperConfig")), "");
        if (!dxwrapperConfig.equals(container.getDXWrapperConfig())) {
            container.setDXWrapperConfig(dxwrapperConfig);
            changed = true;
        }

        String box64Preset = or(resolve(EXTRA_BOX64_PRESET, manifest.get("box64Preset")), Box64Preset.DEFAULT);
        if (!box64Preset.equals(container.getBox64Preset())) {
            container.setBox64Preset(box64Preset);
            changed = true;
        }

        // Merged onto the defaults rather than replacing them: those carry the Mesa/Zink/esync
        // settings the renderer depends on, and a game that only wants to set a locale would
        // otherwise silently turn them all off.
        EnvVars envVars = new EnvVars(Container.DEFAULT_ENV_VARS);
        String extraEnvVars = resolve(EXTRA_ENV_VARS, manifest.get("envVars"));
        if (extraEnvVars != null) envVars.putAll(new EnvVars(extraEnvVars));
        if (!envVars.toString().equals(container.getEnvVars())) {
            container.setEnvVars(envVars.toString());
            changed = true;
        }

        if (changed) container.saveData();
    }

    /** Pulls {@code LC_ALL}/{@code LANG} out of an env var string, or null if neither is set. */
    private static String localeOf(String envVars) {
        if (envVars == null) return null;
        for (String token : envVars.split("\\s+")) {
            int eq = token.indexOf('=');
            if (eq <= 0) continue;
            String name = token.substring(0, eq);
            if (name.equals("LC_ALL") || name.equals("LANG")) return token.substring(eq+1);
        }
        return null;
    }

    private static String or(String value, String fallback) {
        return value != null ? value : fallback;
    }

    /** Intent extra if the caller sent a non-blank one, else the archive's value, else null. */
    private String resolve(String extraName, String manifestValue) {
        String value = getIntent().getStringExtra(extraName);
        if (value == null || value.trim().isEmpty()) value = manifestValue;
        if (value == null) return null;
        value = value.trim();
        return value.isEmpty() ? null : value;
    }

    private void finishWithError(String message) {
        // Also logged: a Toast is useless to whatever is driving this activity, and every rejection
        // here otherwise looks identical from the outside (activity opens, closes, nothing happens).
        Log.e(TAG, message);
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        setResult(Activity.RESULT_CANCELED);
        finish();
    }

    private void finishWithErrorOnUiThread(String message) {
        handler.post(() -> {
            preloaderDialog.close();
            finishWithError(message);
        });
    }
}
