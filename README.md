# Happ public subscription aggregator

This repository combines public V2Ray/Xray subscription feeds into one Happ-compatible Base64 subscription.

## Add to Happ

Use this subscription URL:

`https://raw.githubusercontent.com/remod824/Vpn/main/dist/happ.txt`

In Happ, add it as a subscription URL. Later, use **Update** in Happ to download the latest published list.

## Refresh schedule

GitHub Actions rebuilds the list every 3 hours and can also be started manually from the **Actions** tab. Duplicate entries are removed. If every source fails, the last published subscription is preserved.

## Sources

See [sources.txt](sources.txt). These are public third-party feeds; the aggregator does not own or operate the proxy servers.

## Important

Public proxy nodes are operated by unknown third parties. They may stop working, log connection metadata, or be malicious. Do not use them for banking, passwords, or other sensitive traffic. A node tested by its source may still be blocked or unreachable on a particular mobile network. Aggregation does not guarantee that a node bypasses an operator's allowlist.

<!-- Initial build trigger -->


Compact Happ subscription (1000 nodes): https://raw.githubusercontent.com/remod824/Vpn/main/dist/happ-1000.txt
