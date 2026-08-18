<p align="center">
	<img src="logo.png" width="376" height="128" alt="DGWinlator Logo" />
</p>

# DGWinlator (app)

App source for [DGWinlator](https://github.com/skyksit/DGWinlator) — a fork of [Winlator](https://github.com/brunodev85/winlator) that lets the DGPlayer app launch Windows (x86_64) games with Wine and Box64 via an exported bridge activity (`com.winlator.bridge.GameLaunchActivity`).

Key differences from upstream:

- `applicationId` is `com.dgplayer` (same 12-byte length as `com.winlator`; all `.tzst` assets are binary-patched accordingly by `scripts/patch_tzst.py` in the root repo), so the fork **coexists** with stock Winlator.
- Bridge intent action `com.dgplayer.action.PLAY_GAME` and signature-level permission `com.dgplayer.permission.LAUNCH_GAME`.
- The Java source package stays `com.winlator.*` (JNI symbols and R/BuildConfig namespace); only the install identity changed.

This branch (`dgplayer-bridge`) is consumed as the `app` submodule of the root repository.

# Credits and Third-party apps

- Winlator by [brunodev85](https://github.com/brunodev85/winlator)
- GLIBC Patches by [Termux Pacman](https://github.com/termux-pacman/glibc-packages)
- Wine ([winehq.org](https://www.winehq.org/))
- Box86/Box64 by [ptitseb](https://github.com/ptitSeb)
- Mesa (Turnip/Zink/VirGL) ([mesa3d.org](https://www.mesa3d.org))
- DXVK ([github.com/doitsujin/dxvk](https://github.com/doitsujin/dxvk))
- VKD3D ([gitlab.winehq.org/wine/vkd3d](https://gitlab.winehq.org/wine/vkd3d))
- CNC DDraw ([github.com/FunkyFr3sh/cnc-ddraw](https://github.com/FunkyFr3sh/cnc-ddraw))

Special thanks to all the developers involved in these projects.
