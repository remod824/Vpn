# Hawk VPN

Hawk VPN is an Android VPN client project built with Kotlin, Jetpack Compose and the libbox Android binding.

## Current implementation

The current 0.4.0 development build includes:

- A redesigned responsive dark home screen with a pulsing animated connect/disconnect button, animated status/icon transitions, compact network/profile tiles and expandable settings.
- Scroll-safe layouts, safe-area insets, weighted rows, and truncation for long server names so controls remain inside narrow screens.
- Android `VpnService` lifecycle, system VPN permission flow and foreground notification.
- libbox 1.14.4 engine dependency and configuration generation for VLESS, VMess, Trojan, Shadowsocks, Hysteria2, TUIC, AnyTLS and SOCKS links.
- Subscription import from `sources.txt`, Base64/plain-text decoding, deduplication and a local cache capped at 1,000 nodes.
- Server search, country labels, favorites, TCP reachability checks, latency sorting and a working-only filter.
- Background subscription refresh scheduled every three hours.
- Network change monitoring and automatic choice between ordinary and specially-labelled profiles. A manual server selection takes priority.

The app is still an early development build. Protocol variants and subscription links are not all guaranteed to work. A TCP port check does not prove the VPN handshake succeeds. Labels such as “white list” are taken from public node names and do not prove compatibility with a mobile operator's allowlist.

## Build APK

GitHub Actions builds the ARM64 debug APK. Open the repository's **Actions** tab, select **Build Hawk VPN APK**, open the latest successful run and download the **Hawk VPN-debug** artifact. This is a debug APK, not a Play Store release.

## Security notes

Public free proxy nodes are operated by unknown third parties. They may log traffic or stop working without notice. Do not use them for banking, passwords, private work data or other sensitive activity.

## Third-party licensing

The app uses the libbox Android binding. See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for the dependency version and license information.

## Existing Happ feed

The original Happ subscription aggregator remains available at:
https://raw.githubusercontent.com/remod824/Vpn/main/dist/happ.txt


## HAWK VPN 0.4.0
- Black-and-red visual theme and hawk brand identity.
- Custom hawk vector emblem used in the launcher and connection control.
- The hawk fades in and pulses red while the VPN is connecting or connected.
