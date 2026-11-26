#!/usr/bin/env python3
"""Fetch public V2Ray/Xray feeds, normalize, deduplicate and publish Happ subscriptions."""
from __future__ import annotations
import base64
import os
import re
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCES = ROOT / "sources.txt"
OUT = ROOT / "dist"
SCHEMES = ("vless://", "vmess://", "trojan://", "ss://", "socks://")
MAX_NODES = 12000
HAPP_NODES = 1000
TIMEOUT = 25

def fetch(url: str) -> str:
    req = urllib.request.Request(url, headers={"User-Agent": "Happ-Subscription-Aggregator/1.0"})
    with urllib.request.urlopen(req, timeout=TIMEOUT) as response:
        return response.read(12_000_000).decode("utf-8", errors="ignore").strip()

def decode_if_base64(value: str) -> str:
    compact = re.sub(r"\s+", "", value)
    if not compact or len(compact) % 4 not in (0, 2, 3):
        return value
    try:
        padded = compact + "=" * ((4 - len(compact) % 4) % 4)
        decoded = base64.b64decode(padded, validate=True).decode("utf-8", errors="ignore")
        if any(s in decoded for s in SCHEMES):
            return decoded
    except Exception:
        pass
    return value

def extract(text: str) -> list[str]:
    text = decode_if_base64(text)
    found = []
    for line in text.splitlines():
        line = line.strip().lstrip("\ufeff")
        if line.startswith(SCHEMES):
            found.append(line)
    return found

def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    urls = [x.strip() for x in SOURCES.read_text().splitlines()
            if x.strip() and not x.lstrip().startswith("#")]
    unique: dict[str, str] = {}
    failures = []
    for url in urls:
        try:
            nodes = extract(fetch(url))
            for node in nodes:
                unique.setdefault(node, node)
            print(f"{url}: {len(nodes)} configs")
        except Exception as exc:
            failures.append(f"{url}: {type(exc).__name__}: {exc}")
            print(f"WARNING: {failures[-1]}")
    nodes = list(unique.values())[:MAX_NODES]
    if not nodes:
        raise SystemExit("No valid configs fetched; keeping previous published files.")
    raw = "\n".join(nodes) + "\n"
    encoded = base64.b64encode(raw.encode()).decode() + "\n"
    (OUT / "happ.txt").write_text(encoded)
    (OUT / "raw.txt").write_text(raw)
    small_nodes = nodes[:HAPP_NODES]
    small_raw = "\n".join(small_nodes) + "\n"
    small_encoded = base64.b64encode(small_raw.encode()).decode() + "\n"
    (OUT / "happ-1000.txt").write_text(small_encoded)
    (OUT / "status.txt").write_text(
        f"Configs: {len(nodes)}\nSources: {len(urls)}\nFailed sources: {len(failures)}\n"
        + "\n".join(failures) + "\n"
    )
    print(f"Published {len(nodes)} unique configs; Happ compact file: {len(small_nodes)} configs.")

if __name__ == "__main__":
    main()
