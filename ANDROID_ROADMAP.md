# AutoVPN — development roadmap

## Current milestone: Android project foundation (0.1.0)

- Native Android project with Kotlin and Jetpack Compose.
- Dark home screen matching the approved first concept.
- Network transport detection (Wi-Fi / cellular) through ConnectivityManager callbacks.
- Three policy modes are represented in the UI:
  - Automatic: normal profile on Wi-Fi, special profile on cellular.
  - Always normal VPN.
  - Always special profile.
- GitHub Actions workflow builds a debug APK and publishes it as an Actions artifact.

## Next milestones

1. Add a maintained sing-box Android core and implement the Android VpnService lifecycle, foreground notification, permission flow, start/stop, and reconnection.
2. Implement subscription ingestion from the configured public feeds; decode Base64/plain-text feeds, parse supported share links, validate and deduplicate nodes.
3. Persist nodes and health metadata in Room; use paging/lazy lists and bounded batches.
4. Add background refresh with WorkManager, cached results, exponential backoff, and last-known-good server.
5. Add manual country/server selection, favorites, latency sorting, and working-only filter.
6. Implement network-mode policy switching safely, with a reconnect when the active network changes.
7. Add tests, privacy disclosures, license notices, and release signing.

## Important implementation notes

- A successful ICMP/TCP latency probe is not proof that a node works through an operator allowlist. Report connection and reachability separately.
- Public third-party nodes are untrusted. Do not use them for sensitive accounts or financial traffic.
- The sing-box Android library is GPL-3.0-or-later. Shipping it requires distributing the corresponding app source under a compatible license and retaining notices.
- Do not publish a release APK until the VPN core, permission flow, and end-to-end traffic tests are complete.
