import csv
import importlib.util
import json
import sys
import tempfile
import threading
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


PROBE = load("channel_probe", Path(__file__).parents[1] / "channel_probe.py")

USER = "fixtureuser"
PASSWORD = "fixture-pass-Zq9"
TS_SEGMENT = bytes([0x47]) + bytes(8000)


def make_handler(routes, hits):
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass

        def do_GET(self):
            path = self.path.split("?")[0]
            hits.append(path)
            route = routes.get(path)
            if callable(route):
                route = route()
            if route is None:
                self.send_response(404)
                self.end_headers()
                return
            code, body = route
            self.send_response(code)
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)

    return Handler


class ChannelProbeTest(unittest.TestCase):
    def serve(self, routes):
        hits = []
        server = ThreadingHTTPServer(("127.0.0.1", 0), make_handler(routes, hits))
        threading.Thread(target=server.serve_forever, daemon=True).start()
        # Cleanups run last-in first-out: stop the loop, then close the socket.
        self.addCleanup(server.server_close)
        self.addCleanup(server.shutdown)
        return "http://127.0.0.1:%d" % server.server_address[1], hits

    def probe(self, routes, path="/c.m3u8"):
        base, hits = self.serve(routes)
        sleeps = []
        result = PROBE.probe(base + path, sleep=sleeps.append)
        return result, hits, sleeps

    def test_working_channel_is_ok(self):
        result, _, _ = self.probe({
            "/c.m3u8": (200, b"#EXTM3U\n#EXTINF:4,\nseg.ts\n"),
            "/seg.ts": (200, TS_SEGMENT),
        })
        self.assertEqual(result, (PROBE.OK, "ok"))

    def test_master_playlist_follows_first_variant(self):
        result, hits, _ = self.probe({
            "/c.m3u8": (200, b"#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nv1.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=2\nv2.m3u8\n"),
            "/v1.m3u8": (200, b"#EXTM3U\nseg.ts\n"),
            "/seg.ts": (200, TS_SEGMENT),
        })
        self.assertEqual(result, (PROBE.OK, "ok"))
        self.assertNotIn("/v2.m3u8", hits)

    def test_http_404_is_dead(self):
        result, _, _ = self.probe({})
        self.assertEqual(result, (PROBE.DEAD, "http404"))

    def test_repeated_403_is_unclear_not_dead_after_three_attempts(self):
        result, hits, sleeps = self.probe({"/c.m3u8": (403, b"")})
        self.assertEqual(result, (PROBE.UNCLEAR, "repeated-403"))
        self.assertEqual(hits.count("/c.m3u8"), PROBE.MAX_403_ATTEMPTS)
        self.assertEqual(sleeps, [PROBE.RETRY_403_DELAY_SECONDS] * (PROBE.MAX_403_ATTEMPTS - 1))

    def test_403_is_retried_and_a_freed_connection_gives_ok(self):
        calls = []

        def busy_twice():
            calls.append(1)
            return (403, b"") if len(calls) <= 2 else (200, b"#EXTM3U\nseg.ts\n")

        result, _, sleeps = self.probe({"/c.m3u8": busy_twice, "/seg.ts": (200, TS_SEGMENT)})
        self.assertEqual(result, (PROBE.OK, "ok"))
        self.assertEqual(len(sleeps), 2)

    def test_pause_after_five_busy_channels_in_a_row(self):
        routes = {"/player_api.php": lambda: (200, json.dumps(
            [{"stream_id": i, "name": "Chan %d" % i} for i in range(1, 6)]).encode())}
        for i in range(1, 6):
            routes["/live/%s/%s/%d.m3u8" % (USER, PASSWORD, i)] = (403, b"")
        base, _ = self.serve(routes)
        with tempfile.TemporaryDirectory() as tmp:
            secret = Path(tmp) / "secret.md"
            secret.write_text("SERVER=%s\nUSERNAME=%s\nPASSWORD=%s\n" % (base, USER, PASSWORD), encoding="utf-8")
            sleeps, logged = [], []
            PROBE.run(secret, Path(tmp) / "scan.csv", sleep=sleeps.append, log=logged.append)
        self.assertEqual(sleeps.count(PROBE.BUSY_PAUSE_SECONDS), 1)
        self.assertTrue(any("busy" in line for line in logged))
        self.assertNotIn(PASSWORD, "\n".join(logged))

    def test_blank_playlist_is_empty(self):
        result, _, _ = self.probe({"/c.m3u8": (200, b"   \n")})
        self.assertEqual(result, (PROBE.EMPTY, "empty-playlist"))

    def test_non_playlist_is_dead(self):
        result, _, _ = self.probe({"/c.m3u8": (200, b"<html>nope</html>")})
        self.assertEqual(result, (PROBE.DEAD, "not-a-playlist"))

    def test_tiny_segment_is_dead(self):
        result, _, _ = self.probe({
            "/c.m3u8": (200, b"#EXTM3U\nseg.ts\n"),
            "/seg.ts": (200, bytes([0x47]) + bytes(100)),
        })
        self.assertEqual(result, (PROBE.DEAD, "segment-too-small"))

    def test_segment_without_sync_byte_is_unclear(self):
        result, _, _ = self.probe({
            "/c.m3u8": (200, b"#EXTM3U\nseg.ts\n"),
            "/seg.ts": (200, bytes(8000)),
        })
        self.assertEqual(result, (PROBE.UNCLEAR, "segment-not-ts"))

    def test_503_is_retried_once_then_succeeds(self):
        calls = []

        def flaky():
            calls.append(1)
            return (503, b"") if len(calls) == 1 else (200, b"#EXTM3U\nseg.ts\n")

        result, _, sleeps = self.probe({"/c.m3u8": flaky, "/seg.ts": (200, TS_SEGMENT)})
        self.assertEqual(result, (PROBE.OK, "ok"))
        self.assertEqual(len(sleeps), 1)

    def test_repeated_503_is_reported(self):
        result, hits, sleeps = self.probe({"/c.m3u8": (503, b"")})
        self.assertEqual(result, (PROBE.UNCLEAR, "repeated-503"))
        self.assertEqual(hits.count("/c.m3u8"), 2)
        self.assertEqual(len(sleeps), 1)

    def test_connection_error_detail_has_no_url(self):
        result = PROBE.probe("http://127.0.0.1:1/live/%s/%s/1.m3u8" % (USER, PASSWORD), sleep=lambda _: None)
        self.assertEqual(result[0], PROBE.UNCLEAR)
        self.assertTrue(result[1].startswith("error-"))
        self.assertNotIn(PASSWORD, result[1])
        self.assertNotIn("127.0.0.1", result[1])

    def test_parse_secret_requires_all_three_values(self):
        text = "# note\nSERVER= example.test:8080/\nUSERNAME=u\nPASSWORD=p\n"
        self.assertEqual(
            PROBE.parse_secret(text),
            {"SERVER": "http://example.test:8080", "USERNAME": "u", "PASSWORD": "p"},
        )
        with self.assertRaises(ValueError):
            PROBE.parse_secret("SERVER=x\nUSERNAME=\nPASSWORD=p\n")

    def test_run_writes_csv_resumes_and_never_leaks_the_login(self):
        routes = {}

        def api():
            rows = [{"stream_id": i, "name": "Chan %d" % i} for i in (1, 2, 3)]
            return 200, json.dumps(rows).encode()

        routes["/player_api.php"] = api
        for i in (1, 2, 3):
            routes["/live/%s/%s/%d.m3u8" % (USER, PASSWORD, i)] = (
                (200, b"#EXTM3U\nseg%d.ts\n" % i) if i != 2 else (404, b"")
            )
            routes["/live/%s/%s/seg%d.ts" % (USER, PASSWORD, i)] = (200, TS_SEGMENT)
        base, _ = self.serve(routes)
        with tempfile.TemporaryDirectory() as tmp:
            secret = Path(tmp) / "secret.md"
            secret.write_text("SERVER=%s\nUSERNAME=%s\nPASSWORD=%s\n" % (base, USER, PASSWORD), encoding="utf-8")
            out = Path(tmp) / "scan.csv"
            logged = []
            counts = PROBE.run(secret, out, limit=2, sleep=lambda _: None, log=logged.append)
            self.assertEqual(counts[PROBE.OK] + counts[PROBE.DEAD], 2)
            counts = PROBE.run(secret, out, sleep=lambda _: None, log=logged.append)
            self.assertEqual(sum(counts.values()), 1)  # resumed: only the third channel was left
            with out.open(newline="", encoding="utf-8") as handle:
                rows = list(csv.DictReader(handle))
            self.assertEqual([r["id"] for r in rows], ["1", "2", "3"])
            self.assertEqual([r["status"] for r in rows], ["OK", "DEAD", "OK"])
            everything = out.read_text(encoding="utf-8") + "\n".join(logged)
            self.assertNotIn(PASSWORD, everything)
            self.assertNotIn(USER, everything)
            self.assertNotIn("127.0.0.1", everything)


if __name__ == "__main__":
    unittest.main()
