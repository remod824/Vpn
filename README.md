# AutoVPN

AutoVPN is an Android VPN client project. The current 0.1.0 milestone establishes the native Android/Compose project and the first dark home-screen design.

## Current status

This is an early UI and Android project foundation, **not yet a working VPN client**. The VPN engine, subscription parser, persistent server catalogue, and real connection flow are still being implemented. The screen intentionally labels the VPN as not connected.

## Build

GitHub Actions builds a debug APK on pushes affecting Android project files and on manual workflow runs. Open the repository's **Actions** tab, select **Build AutoVPN APK**, and download the **AutoVPN-debug** artifact from a successful run.

## Planned features

- Automatic profile policy for Wi-Fi and cellular networks.
- Manual server list, favorites, latency sorting, and working-only filter.
- Subscription refresh, deduplication, local storage, and background checks.
- Memory-conscious loading and reconnection to the last known-good server.

See ANDROID_ROADMAP.md for implementation stages.

## Existing Happ feed

The original Happ subscription aggregator remains available at:
https://raw.githubusercontent.com/remod824/Vpn/main/dist/happ.txt
