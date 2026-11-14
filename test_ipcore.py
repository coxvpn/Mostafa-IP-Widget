import json
import os
import tempfile
import unittest

import ipcore


class FakeResponse:
    def __init__(self, payload=None, text="", status=200):
        self._payload, self.text, self.status = payload, text, status

    def raise_for_status(self):
        if self.status >= 400:
            raise RuntimeError(f"HTTP {self.status}")

    def json(self):
        if self._payload is None:
            raise ValueError("no json")
        return self._payload


class FakeHttp:
    """Answers each URL from a dict; anything missing counts as a network error."""

    def __init__(self, answers):
        self.answers, self.calls = answers, []

    def get(self, url, **kwargs):
        self.calls.append(url)
        answer = self.answers.get(url)
        if answer is None:
            raise ConnectionError("offline")
        return answer


class SettingsTests(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.path = os.path.join(self.dir.name, "settings.json")

    def tearDown(self):
        self.dir.cleanup()

    def test_defaults_when_file_missing(self):
        s = ipcore.Settings(self.path).load()
        self.assertEqual(s.data, ipcore.DEFAULT_SETTINGS)

    def test_roundtrip(self):
        s = ipcore.Settings(self.path)
        s.set("x", 120)
        s.set("opacity", 0.5)
        again = ipcore.Settings(self.path).load()
        self.assertEqual(again.get("x"), 120)
        self.assertEqual(again.get("opacity"), 0.5)

    def test_bad_values_are_replaced(self):
        with open(self.path, "w") as fh:
            json.dump({"opacity": "abc", "interval": 1, "x": True, "show_details": "yes",
                       "unknown": 1}, fh)
        s = ipcore.Settings(self.path).load()
        self.assertEqual(s.get("opacity"), 0.7)
        self.assertEqual(s.get("interval"), 5)  # clamped
        self.assertIsNone(s.get("x"))
        self.assertTrue(s.get("show_details"))
        self.assertNotIn("unknown", s.data)

    def test_corrupt_file_is_ignored(self):
        with open(self.path, "w") as fh:
            fh.write("{not json")
        self.assertEqual(ipcore.Settings(self.path).load().data, ipcore.DEFAULT_SETTINGS)


class PublicIpTests(unittest.TestCase):
    def test_first_source_works(self):
        http = FakeHttp({ipcore.IP_ENDPOINTS[0]: FakeResponse({"ip": "8.8.8.8"})})
        self.assertEqual(ipcore.fetch_public_ip(http), "8.8.8.8")
        self.assertEqual(len(http.calls), 1)

    def test_falls_back_to_next_source(self):
        http = FakeHttp({ipcore.IP_ENDPOINTS[1]: FakeResponse(text="1.2.3.4\n")})
        self.assertEqual(ipcore.fetch_public_ip(http), "1.2.3.4")

    def test_garbage_answer_is_rejected(self):
        http = FakeHttp({url: FakeResponse(text="<html>captive portal</html>")
                         for url in ipcore.IP_ENDPOINTS[1:]})
        self.assertIsNone(ipcore.fetch_public_ip(http))

    def test_offline(self):
        self.assertIsNone(ipcore.fetch_public_ip(FakeHttp({})))

    def test_ipv6_is_accepted(self):
        http = FakeHttp({ipcore.IP_ENDPOINTS[0]: FakeResponse({"ip": "2001:4860:4860::8888"})})
        self.assertEqual(ipcore.fetch_public_ip(http), "2001:4860:4860::8888")


class LocationTests(unittest.TestCase):
    def test_ipwho(self):
        payload = {"success": True, "country": "Germany", "country_code": "de",
                   "city": "Berlin", "connection": {"isp": "Example ISP"}}
        http = FakeHttp({"https://ipwho.is/1.1.1.1": FakeResponse(payload)})
        self.assertEqual(ipcore.lookup_location("1.1.1.1", http),
                         {"country": "Germany", "code": "DE", "city": "Berlin", "isp": "Example ISP"})

    def test_fallback_to_second_source(self):
        http = FakeHttp({
            "https://ipwho.is/1.1.1.1": FakeResponse({"success": False}),
            "https://ipapi.co/1.1.1.1/json/": FakeResponse(
                {"country_name": "France", "country_code": "FR", "city": "Paris", "org": "Org"}),
        })
        self.assertEqual(ipcore.lookup_location("1.1.1.1", http)["code"], "FR")

    def test_all_sources_fail(self):
        self.assertIsNone(ipcore.lookup_location("1.1.1.1", FakeHttp({})))

    def test_strange_values_are_cleaned(self):
        payload = {"success": True, "country": "Ger\x00many", "country_code": "../x",
                   "city": "A" * 500, "connection": {}}
        http = FakeHttp({"https://ipwho.is/1.1.1.1": FakeResponse(payload)})
        info = ipcore.lookup_location("1.1.1.1", http)
        self.assertEqual(info["country"], "Germany")
        self.assertEqual(info["code"], "")  # never used to build a file path
        self.assertEqual(len(info["city"]), 60)


class MonitorTests(unittest.TestCase):
    def make(self, ips, location=None):
        """A monitor whose IP answers come from the list `ips` (None = offline)."""
        seen, looked = [], []
        answers = iter(ips)

        def lookup(ip):
            looked.append(ip)
            return location

        mon = ipcore.IPMonitor(seen.append, lambda: 10, fetch_ip=lambda: next(answers), lookup=lookup)
        return mon, seen, looked

    LOC = {"country": "Germany", "code": "DE", "city": "Berlin", "isp": "X"}

    def test_reports_once_and_looks_up_once(self):
        mon, seen, looked = self.make(["1.1.1.1", "1.1.1.1", "1.1.1.1"], self.LOC)
        for _ in range(3):
            mon.poll_once()
        self.assertEqual(len(seen), 1)
        self.assertEqual(looked, ["1.1.1.1"])
        self.assertEqual(seen[0]["country"], "Germany")

    def test_new_ip_triggers_new_lookup(self):
        mon, seen, looked = self.make(["1.1.1.1", "2.2.2.2"], self.LOC)
        mon.poll_once()
        mon.poll_once()
        self.assertEqual(looked, ["1.1.1.1", "2.2.2.2"])
        self.assertEqual(len(seen), 2)

    def test_failed_details_are_retried(self):
        mon, seen, looked = self.make(["1.1.1.1", "1.1.1.1"], None)
        mon.poll_once()
        mon.poll_once()
        self.assertEqual(looked, ["1.1.1.1", "1.1.1.1"])
        self.assertFalse(seen[0]["located"])

    def test_one_failed_check_does_not_show_offline(self):
        mon, seen, _ = self.make(["1.1.1.1", None, "1.1.1.1"], self.LOC)
        for _ in range(3):
            mon.poll_once()
        self.assertEqual([s["online"] for s in seen], [True])

    def test_two_failed_checks_show_offline(self):
        mon, seen, _ = self.make(["1.1.1.1", None, None], self.LOC)
        for _ in range(3):
            mon.poll_once()
        self.assertEqual([s["online"] for s in seen], [True, False])

    def test_offline_at_startup_is_shown_immediately(self):
        mon, seen, _ = self.make([None])
        mon.poll_once()
        self.assertEqual([s["online"] for s in seen], [False])

    def test_force_always_reports(self):
        mon, seen, looked = self.make(["1.1.1.1", "1.1.1.1"], self.LOC)
        mon.poll_once()
        mon.poll_once(force=True)
        self.assertEqual(len(seen), 2)
        self.assertEqual(len(looked), 2)


class MiscTests(unittest.TestCase):
    def test_startup_helpers_are_safe_off_windows(self):
        if not ipcore.IS_WINDOWS:
            self.assertFalse(ipcore.is_startup_enabled())
            self.assertFalse(ipcore.set_startup(True))
            self.assertTrue(ipcore.acquire_single_instance())

    def test_startup_command_is_quoted(self):
        self.assertTrue(ipcore.startup_command().startswith('"'))


if __name__ == "__main__":
    unittest.main()
