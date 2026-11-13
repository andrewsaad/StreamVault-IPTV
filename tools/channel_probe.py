#!/usr/bin/env python3
"""Scan every live channel of an Xtream provider and record which ones work.

Mirrors the app's ChannelHealthProbe: fetch the HLS playlist, follow the first variant,
then read the first bytes of the first segment. One connection at a time, because the
provider allows only one: never run it while the in-app scan or playback is active.

The tool never prints or writes the provider login or any stream URL. The report is a CSV
with channel id, name, status and a fixed-vocabulary detail only. Re-running resumes: a channel
whose latest CSV row is OK, DEAD or EMPTY is skipped; UNCLEAR channels are scanned again.
An UNCLEAR row with detail provider-403 means the provider refused the request (account busy or
address limited): it says nothing about the channel, so stop and retry later instead of looping.

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
# Xtream answers 403 for a restricted channel, and also while another device holds the single
# connection. Retry once, then ask the account whether it is really busy before waiting.
RETRY_403_DELAY_SECONDS = 5.0
BUSY_WAIT_SECONDS = 30.0
MAX_BUSY_WAITS = 10

OK, DEAD, EMPTY, UNCLEAR = "OK", "DEAD", "EMPTY", "UNCLEAR"
# A channel whose latest row has one of these statuses is done. UNCLEAR is retried on a re-run.
FINAL_STATUSES = (OK, DEAD, EMPTY)
# Playlist lines can name any scheme (file:, ftp:, data:). Only these are ever opened.
ALLOWED_URL_SCHEMES = ("http", "https")


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


def _is_http_url(url: str) -> bool:
    return urllib.parse.urlsplit(url).scheme.lower() in ALLOWED_URL_SCHEMES


# Only http and https: the default opener also speaks ftp, file and data, and follows a redirect to them,
# which would bypass the scheme check on playlist lines. The app's OkHttp probe only follows http(s) too.
_OPENER = urllib.request.OpenerDirector()
for _handler in (
    urllib.request.HTTPHandler,
    urllib.request.HTTPSHandler,
    urllib.request.HTTPRedirectHandler,
    urllib.request.HTTPDefaultErrorHandler,
    urllib.request.HTTPErrorProcessor,
    urllib.request.UnknownHandler,
):
    _OPENER.add_handler(_handler())


def _open(url: str, headers=None):
    request = urllib.request.Request(url, headers=headers or {})
    return _OPENER.open(request, timeout=TIMEOUT_SECONDS)


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
        if not _is_http_url(variant_url):  # treated like an unusable line: no-variant
            return DEAD, "no-variant"
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
    if not _is_http_url(segment_url):  # treated like an unusable line: no-segment
        return DEAD, "no-segment"
    code, _, data = _fetch(segment_url, MAX_SEGMENT_BYTES, {"Range": "bytes=0-%d" % (MAX_SEGMENT_BYTES - 1)})
    verdict = status_for_http_code(code)
    if verdict:
        return verdict, "segment-http%d" % code
    if len(data) < MIN_SEGMENT_BYTES:
        return DEAD, "segment-too-small"
    if data[0] != MPEG_TS_SYNC_BYTE:
        return UNCLEAR, "segment-not-ts"
    return OK, "ok"


def probe(playlist_url: str, sleep=time.sleep, busy_check=None):
    """Returns (status, detail).

    One retry after a 503 (a second one is reported as repeated-503). A 403 is retried once; if it
    persists, busy_check() says whether the account is at its connection limit. Busy: wait and
    retry (up to MAX_BUSY_WAITS, then account-busy). Not busy: the channel is restricted, so
    provider-403.
    """
    retried_503 = False
    retried_403 = False
    busy_waits = 0
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
            if not retried_403:
                retried_403 = True
                sleep(RETRY_403_DELAY_SECONDS)
                continue
            if busy_check is not None and busy_check():
                if busy_waits >= MAX_BUSY_WAITS:
                    return status, "account-busy"
                busy_waits += 1
                sleep(BUSY_WAIT_SECONDS)
                continue
            return status, "provider-403"
        return status, detail


def account_busy(server: str, username: str, password: str) -> bool:
    """True when the account is at its connection limit. Any failure counts as not busy."""
    query = urllib.parse.urlencode({"username": username, "password": password})
    try:
        with _open(server + "/player_api.php?" + query) as response:
            info = json.loads(response.read().decode("utf-8", "replace")).get("user_info", {})
        return int(info["active_cons"]) >= int(info["max_connections"])
    except Exception:
        return False


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
    """Ids whose latest row has a final status (OK, DEAD, EMPTY).

    The CSV is append-only: a re-probed channel gets a new row and the later row wins. So an
    UNCLEAR channel re-probed to OK is done, and an OK channel re-probed to UNCLEAR is scanned again.
    """
    if not out_path.exists():
        return set()
    latest = {}
    with out_path.open(newline="", encoding="utf-8") as handle:
        for row in csv.DictReader(handle):
            if row.get("id", "").isdigit():
                latest[int(row["id"])] = row.get("status", "")
    return {cid for cid, status in latest.items() if status in FINAL_STATUSES}


def run(secret_path: Path, out_path: Path, limit=None, sleep=time.sleep, log=print):
    login = parse_secret(secret_path.read_text(encoding="utf-8"))
    server, username, password = login["SERVER"], login["USERNAME"], login["PASSWORD"]
    channels = fetch_channels(server, username, password)
    listed = {cid for cid, _ in channels}
    already = done_ids(out_path) & listed  # ids the provider no longer lists are not counted
    todo = [(cid, name) for cid, name in channels if cid not in already]
    if limit is not None:
        todo = todo[:limit]
    log("channels: %d total, %d already scanned, %d to scan now" % (len(channels), len(already), len(todo)))
    new_file = not out_path.exists() or out_path.stat().st_size == 0
    counts = {OK: 0, DEAD: 0, EMPTY: 0, UNCLEAR: 0}
    def busy_check():
        return account_busy(server, username, password)

    with out_path.open("a", newline="", encoding="utf-8") as handle:
        writer = csv.writer(handle)
        if new_file:
            writer.writerow(["id", "name", "status", "detail"])
        for index, (cid, name) in enumerate(todo, 1):
            status, detail = probe(
                playlist_url(server, username, password, cid), sleep=sleep, busy_check=busy_check
            )
            writer.writerow([cid, name, status, detail])
            handle.flush()
            counts[status] += 1
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
