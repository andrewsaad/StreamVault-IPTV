#!/usr/bin/env python3
"""Scan every live channel of an Xtream provider and record which ones work.

Mirrors the app's ChannelHealthProbe: fetch the HLS playlist, follow the first variant,
then read the first bytes of the first segment. One connection at a time, because the
provider allows only one: never run it while the in-app scan or playback is active.

The tool never prints or writes the provider login or any stream URL. The report is a CSV
with channel id, name, status and a fixed-vocabulary detail only. Re-running resumes: ids
already in the CSV are skipped.

    python tools/channel_probe.py --secret secret.md --out channel_scan.csv
    python tools/channel_probe.py --secret secret.md --out channel_scan.csv --limit 50
"""
from __future__ import annotations

import argparse
import csv
import json
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

TIMEOUT_SECONDS = 20
MAX_PLAYLIST_BYTES = 256 * 1024
MAX_SEGMENT_BYTES = 64 * 1024
MIN_SEGMENT_BYTES = 4096
MPEG_TS_SYNC_BYTE = 0x47
RETRY_503_DELAY_SECONDS = 5.0
# Xtream answers 403 while another device holds the single connection (or the account is blocked).
RETRY_403_DELAY_SECONDS = 10.0
MAX_403_ATTEMPTS = 3
BUSY_CHANNELS_BEFORE_PAUSE = 5
BUSY_PAUSE_SECONDS = 120.0

OK, DEAD, EMPTY, UNCLEAR = "OK", "DEAD", "EMPTY", "UNCLEAR"


def parse_secret(text: str) -> dict:
    values = {}
    for line in text.splitlines():
        match = re.match(r"^\s*(SERVER|USERNAME|PASSWORD)\s*=\s*(.*?)\s*$", line)
        if match and match.group(2):
            values[match.group(1)] = match.group(2)
    missing = [key for key in ("SERVER", "USERNAME", "PASSWORD") if key not in values]
    if missing:
        raise ValueError("secret file is missing: " + ", ".join(missing))
    server = values["SERVER"].rstrip("/")
    if "://" not in server:
        server = "http://" + server
    values["SERVER"] = server
    return values


def status_for_http_code(code: int):
    """None means the response is usable. 401/403 are not a verdict (connection limit, expired account)."""
    if 200 <= code < 300:
        return None
    if code in (404, 410):
        return DEAD
    return UNCLEAR


def _first_entry(text: str):
    for line in text.splitlines():
        line = line.strip()
        if line and not line.startswith("#"):
            return line
    return None


def _open(url: str, headers=None):
    request = urllib.request.Request(url, headers=headers or {})
    return urllib.request.urlopen(request, timeout=TIMEOUT_SECONDS)


def _fetch(url: str, max_bytes: int, headers=None):
    """Returns (http_code, final_url, body_bytes). Never raises for HTTP error codes."""
    try:
        with _open(url, headers) as response:
            return response.status, response.geturl(), response.read(max_bytes)
    except urllib.error.HTTPError as error:
        return error.code, url, b""


def _classify_once(playlist_url: str):
    code, final_url, body = _fetch(playlist_url, MAX_PLAYLIST_BYTES)
    verdict = status_for_http_code(code)
    if verdict:
        return verdict, "http%d" % code
    text = body.decode("utf-8", "replace")
    if not text.strip():
        return EMPTY, "empty-playlist"
    if "#EXTM3U" not in text:
        return DEAD, "not-a-playlist"
    entry = _first_entry(text)
    if "#EXT-X-STREAM-INF" in text:
        if entry is None:
            return DEAD, "no-variant"
        variant_url = urllib.parse.urljoin(final_url, entry)
        code, final_url, body = _fetch(variant_url, MAX_PLAYLIST_BYTES)
        verdict = status_for_http_code(code)
        if verdict:
            return verdict, "variant-http%d" % code
        text = body.decode("utf-8", "replace")
        if not text.strip():
            return EMPTY, "empty-variant"
        if "#EXTM3U" not in text:
            return DEAD, "variant-not-a-playlist"
        entry = _first_entry(text)
    if entry is None:
        return DEAD, "no-segment"
    segment_url = urllib.parse.urljoin(final_url, entry)
    code, _, data = _fetch(segment_url, MAX_SEGMENT_BYTES, {"Range": "bytes=0-%d" % (MAX_SEGMENT_BYTES - 1)})
    verdict = status_for_http_code(code)
    if verdict:
        return verdict, "segment-http%d" % code
    if len(data) < MIN_SEGMENT_BYTES:
        return DEAD, "segment-too-small"
    if data[0] != MPEG_TS_SYNC_BYTE:
        return UNCLEAR, "segment-not-ts"
    return OK, "ok"


def probe(playlist_url: str, sleep=time.sleep):
    """Returns (status, detail).

    One retry after a 503 (a second one is reported as repeated-503). A 403 means the connection
    is busy, so it is retried with a delay, up to MAX_403_ATTEMPTS, then reported as repeated-403.
    """
    retried_503 = False
    attempts_403 = 0
    while True:
        try:
            status, detail = _classify_once(playlist_url)
        except Exception as error:  # IO errors and timeouts say nothing about the channel.
            # Only the exception class name: its message can contain the url and the login.
            return UNCLEAR, "error-" + type(error).__name__
        if detail.endswith("http503"):
            if retried_503:
                return status, "repeated-503"
            retried_503 = True
            sleep(RETRY_503_DELAY_SECONDS)
            continue
        if detail.endswith("http403"):
            attempts_403 += 1
            if attempts_403 >= MAX_403_ATTEMPTS:
                return status, "repeated-403"
            sleep(RETRY_403_DELAY_SECONDS)
            continue
        return status, detail


def fetch_channels(server: str, username: str, password: str):
    query = urllib.parse.urlencode(
        {"username": username, "password": password, "action": "get_live_streams"}
    )
    with _open(server + "/player_api.php?" + query) as response:
        rows = json.loads(response.read().decode("utf-8", "replace"))
    channels = []
    for row in rows:
        try:
            channels.append((int(row["stream_id"]), str(row.get("name", "")).strip()))
        except (KeyError, TypeError, ValueError):
            continue
    return channels


def playlist_url(server: str, username: str, password: str, stream_id: int) -> str:
    quoted = urllib.parse.quote
    return "%s/live/%s/%s/%d.m3u8" % (server, quoted(username, safe=""), quoted(password, safe=""), stream_id)


def done_ids(out_path: Path) -> set:
    if not out_path.exists():
        return set()
    with out_path.open(newline="", encoding="utf-8") as handle:
        return {int(row["id"]) for row in csv.DictReader(handle) if row.get("id", "").isdigit()}


def run(secret_path: Path, out_path: Path, limit=None, sleep=time.sleep, log=print):
    login = parse_secret(secret_path.read_text(encoding="utf-8"))
    server, username, password = login["SERVER"], login["USERNAME"], login["PASSWORD"]
    channels = fetch_channels(server, username, password)
    already = done_ids(out_path)
    todo = [(cid, name) for cid, name in channels if cid not in already]
    if limit is not None:
        todo = todo[:limit]
    log("channels: %d total, %d already scanned, %d to scan now" % (len(channels), len(already), len(todo)))
    new_file = not out_path.exists() or out_path.stat().st_size == 0
    counts = {OK: 0, DEAD: 0, EMPTY: 0, UNCLEAR: 0}
    busy_in_a_row = 0
    with out_path.open("a", newline="", encoding="utf-8") as handle:
        writer = csv.writer(handle)
        if new_file:
            writer.writerow(["id", "name", "status", "detail"])
        for index, (cid, name) in enumerate(todo, 1):
            status, detail = probe(playlist_url(server, username, password, cid), sleep=sleep)
            writer.writerow([cid, name, status, detail])
            handle.flush()
            counts[status] += 1
            busy_in_a_row = busy_in_a_row + 1 if detail == "repeated-403" else 0
            if busy_in_a_row >= BUSY_CHANNELS_BEFORE_PAUSE:
                log("account looks busy (repeated 403): pausing %d s" % BUSY_PAUSE_SECONDS)
                sleep(BUSY_PAUSE_SECONDS)
                busy_in_a_row = 0
            if index % 50 == 0:
                log("scanned %d/%d  %s" % (index, len(todo), counts))
    log("done: %s" % counts)
    return counts


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--secret", default="secret.md", type=Path)
    parser.add_argument("--out", default="channel_scan.csv", type=Path)
    parser.add_argument("--limit", type=int, default=None, help="scan at most this many new channels")
    args = parser.parse_args(argv)
    try:
        run(args.secret, args.out, args.limit)
    except KeyboardInterrupt:
        print("stopped; run again to resume")
        return 130
    except Exception as error:
        print("scan failed: " + type(error).__name__, file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
