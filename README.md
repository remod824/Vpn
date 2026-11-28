# HAWK VPN

HAWK VPN is an Android VPN client built with Kotlin, Jetpack Compose and the libbox Android binding.

## Current implementation

- Black-and-red interface with the HAWK emblem and animated red activation effect.
- Android VPN service lifecycle and system VPN permission flow.
- Server catalogue with search, country labels, favorites, TCP reachability checks and latency sorting.
- Server picker contains only the ordinary server list and favorites; there are no allowlist-specific modes or profile selectors.
- Background catalogue refresh through WorkManager.
- Connection selection uses the manually selected server, or an available server when no server has been selected.
- Supported link formats include VLESS, VMess, Trojan, Shadowsocks, Hysteria2, TUIC, AnyTLS and SOCKS.

A TCP port check only indicates that a port responds; it does not guarantee a successful VPN handshake. Only use VPN endpoints you own or are authorized to use.

## Build APK

GitHub Actions builds a debug APK. Open the repository's Actions tab, select the latest successful **Build HAWK VPN APK** run and download the **HAWK-VPN-debug** artifact. A debug APK is not a Play Store release.

## Third-party licensing

The app uses the libbox Android binding. See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for dependency and license information.
