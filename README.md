<p align="center">
	<img src="logo.png" width="376" height="128" alt="Winlator Logo" />
</p>

# DGWinlator

DGWinlator is a fork of [Winlator](https://github.com/brunodev85/winlator) — an Android application that lets you run Windows (x86_64) applications with Wine and Box86/Box64 — extended with a **DGPlayer bridge** so that the DGPlayer app can launch Windows games directly inside this container runtime.

The fork installs as **`com.dgplayer`** and therefore **coexists with stock Winlator**. The applicationId is deliberately the same byte length (12) as `com.winlator`: the prebuilt rootfs embeds `/data/data/<applicationId>/...` absolute paths (including the glibc ELF interpreter), and every shipped `.tzst` asset — rootfs, box64, graphics drivers, and the runtime-downloadable `installable_components` — has been binary-patched in place by [`scripts/patch_tzst.py`](scripts/patch_tzst.py). Any future rename must keep the 12-byte length and rerun that script.

## What's different from upstream Winlator

Upstream Winlator has no external launch surface: `XServerDisplayActivity` is not exported and `MainActivity` only accepts navigation extras. This fork adds an exported entry point and supporting components under `com.winlator.bridge`:

- **`GameLaunchActivity`** — the bridge contract. It resolves a shared `DGPlayer` container, imports the game payload once, applies per-game settings (graphics driver, DX wrapper, Box64 preset, screen size, environment variables, etc.) onto the container, and hands off to `XServerDisplayActivity`.
- **`PayloadInstaller`** — imports a game payload from a content URI into the container's C: drive (`C:\DGPlayer`).
- **`GameManifest`** — per-game metadata and launch presets.
- **`RegistryImporter`** — imports game-specific registry entries into the Wine prefix.
- **`CjkFontSubstitutes`** — CJK (Korean/Japanese/Chinese) font substitution so games render non-Latin text correctly.
- **Caller trust** — only apps signed with the same certificate (i.e. DGPlayer/dsam3) may launch games; a `signature`-level permission (`com.winlator.permission.LAUNCH_GAME`) is also declared for release hardening.

### Launch intent

```
action:     com.dgplayer.action.PLAY_GAME
component:  com.dgplayer/com.winlator.bridge.GameLaunchActivity
extras:     game_id, title, content_uri, exe, exe_args, screen_size,
            graphics_driver, dxwrapper, dxwrapper_config, box64_preset,
            env_vars, force_fullscreen
```

The Java source package stays `com.winlator.*` (JNI symbol names and the R/BuildConfig namespace depend on it); only the applicationId, intent action, and permission carry the `com.dgplayer` identity.

## Repository layout

This repository uses git submodules:

| Path | Description |
|------|-------------|
| `app` | The Android app source — points at this repository's [`dgplayer-bridge`](https://github.com/skyksit/DGWinlator/tree/dgplayer-bridge) branch (the bridge commits do not exist upstream) |
| `vortek` | [Vortek](https://github.com/brunodev85/vortek) graphics driver |
| `gladio` | [Gladio](https://github.com/brunodev85/gladio) |

The `vortek` and `gladio` submodules are upstream repositories and are left untouched: the guest-side driver binaries shipped in `app/app/src/main/assets/graphics_driver/*.tzst` are already binary-patched for `com.dgplayer`. If you ever rebuild those drivers from source, edit their `include/winlator.h` / `include/vortek.h` / `include/gladio.h` path defines (and `build.sh`) to the `com.dgplayer` prefix first — the in-tree copies under `app/app/src/main/cpp/**` already carry it.

`scripts/` holds the fork tooling: `patch_tzst.py` (asset path rebranding with built-in verification) and `gen_branding.py` (logo and launcher icon generation).

## Building

1. Clone with submodules:
   ```
   git clone --recurse-submodules https://github.com/skyksit/DGWinlator.git
   ```
2. Open the `app` directory in Android Studio and build, or run:
   ```
   cd app && ./gradlew assembleDebug
   ```

## Useful Tips

- If you are experiencing performance issues, try changing the Box64 preset to `Performance` in Container Settings -> Advanced Tab.
- For applications that use .NET Framework, try installing `Wine Mono` found in Start Menu -> System Tools -> Installers.
- If some older games don't open, try adding the environment variable `MESA_EXTENSION_MAX_YEAR=2003` in Container Settings -> Environment Variables.
- To display low resolution games correctly, try enabling the `Force Fullscreen` option in the shortcut settings.
- To improve stability in games that use Unity Engine, try changing the Box64 preset to `Stability` or add the exec argument `-force-gfx-direct` in the shortcut settings.
- If you are experiencing audio crackling, try increasing the average latency in ALSA/PulseAudio configuration. Old games like Unreal Gold resolve audio issues by increasing this value to 90ms.

## Credits and Third-party apps

- Winlator by [brunodev85](https://github.com/brunodev85/winlator)
- GLIBC Patches by [Termux Pacman](https://github.com/termux-pacman/glibc-packages)
- Wine ([winehq.org](https://www.winehq.org/))
- Box86/Box64 by [ptitseb](https://github.com/ptitSeb)
- Mesa (Turnip/Zink/VirGL) ([mesa3d.org](https://www.mesa3d.org))
- DXVK ([github.com/doitsujin/dxvk](https://github.com/doitsujin/dxvk))
- VKD3D ([gitlab.winehq.org/wine/vkd3d](https://gitlab.winehq.org/wine/vkd3d))
- CNC DDraw ([github.com/FunkyFr3sh/cnc-ddraw](https://github.com/FunkyFr3sh/cnc-ddraw))

Special thanks to all the developers involved in these projects.
