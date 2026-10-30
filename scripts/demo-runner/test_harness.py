"""Unit tests for the demo runner. They do not need the service, Kafka or PostgreSQL.

Run from this directory: python -m unittest -v test_harness
"""

import hashlib
import hmac
import json
import tempfile
import threading
import unittest
from http.server import ThreadingHTTPServer
from pathlib import Path
from urllib import request

from receiver import Handler, status_for, verify
import runner

HERE = Path(__file__).resolve().parent


def sign(secret, body, timestamp="1730000000"):
    digest = hmac.new(secret.encode("utf-8"), timestamp.encode("utf-8") + b"." + body, hashlib.sha256).hexdigest()
    return f"t={timestamp},v1={digest}"


class VerifyTest(unittest.TestCase):

    def test_accepts_a_correct_signature(self):
        body = b'{"event_id":"e1"}'
        self.assertTrue(verify(sign("whsec_a", body), body, "whsec_a"))

    def test_rejects_a_changed_body(self):
        header = sign("whsec_a", b'{"amount":100}')
        self.assertFalse(verify(header, b'{"amount":101}', "whsec_a"))

    def test_rejects_the_wrong_secret(self):
        body = b"{}"
        self.assertFalse(verify(sign("whsec_a", body), body, "whsec_b"))

    def test_rejects_a_missing_header_or_secret(self):
        self.assertFalse(verify("", b"{}", "whsec_a"))
        self.assertFalse(verify(sign("whsec_a", b"{}"), b"{}", None))

    def test_rejects_a_header_without_both_parts(self):
        self.assertFalse(verify("t=1730000000", b"{}", "whsec_a"))
        self.assertFalse(verify("v1=abc", b"{}", "whsec_a"))


class RoutingTest(unittest.TestCase):

    def test_fail_paths_answer_500(self):
        self.assertEqual(500, status_for("/s/run1/fail-retries-exhausted/fail"))

    def test_other_paths_answer_200(self):
        self.assertEqual(200, status_for("/s/run1/ok/ok"))
        self.assertEqual(200, status_for("/s/run1/ok"))


class ReceiverServerTest(unittest.TestCase):
    """Starts the real handler on a free port and checks what it writes to the log."""

    def setUp(self):
        self._dir = tempfile.TemporaryDirectory()
        base = Path(self._dir.name)
        self.secrets = base / "secrets.json"
        self.log = base / "requests.jsonl"
        self.secrets.write_text(json.dumps({"/s/r/ok/ok": "whsec_test"}), encoding="utf-8")
        Handler.secrets_file = str(self.secrets)
        Handler.log_file = str(self.log)
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.port = self.server.server_address[1]
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self._dir.cleanup()

    def post(self, path, body, header):
        req = request.Request(f"http://127.0.0.1:{self.port}{path}", data=body, method="POST",
                              headers={"Cobre-Signature": header, "Content-Type": "application/json"})
        try:
            with request.urlopen(req, timeout=5) as response:
                return response.status
        except Exception as ex:  # urllib raises on 5xx
            return ex.code

    def records(self):
        return [json.loads(line) for line in self.log.read_text(encoding="utf-8").splitlines()]

    def test_logs_status_length_and_signature_validity(self):
        body = b'{"event_id":"e1"}'
        self.assertEqual(200, self.post("/s/r/ok/ok", body, sign("whsec_test", body)))
        record = self.records()[-1]
        self.assertEqual("/s/r/ok/ok", record["path"])
        self.assertEqual(200, record["status"])
        self.assertEqual(len(body), record["body_length"])
        self.assertTrue(record["signature_valid"])

    def test_marks_a_bad_signature_without_rejecting_the_request(self):
        body = b"{}"
        self.assertEqual(200, self.post("/s/r/ok/ok", body, sign("whsec_wrong", body)))
        self.assertFalse(self.records()[-1]["signature_valid"])

    def test_fail_path_answers_500_and_is_logged(self):
        self.secrets.write_text(json.dumps({"/s/r/f/fail": "whsec_test"}), encoding="utf-8")
        body = b"{}"
        self.assertEqual(500, self.post("/s/r/f/fail", body, sign("whsec_test", body)))
        self.assertEqual(500, self.records()[-1]["status"])


class ScenarioFileTest(unittest.TestCase):

    def setUp(self):
        self.scenarios = json.loads((HERE / "scenarios.json").read_text(encoding="utf-8"))

    def test_every_scenario_has_an_id_and_an_expectation(self):
        for scenario in self.scenarios:
            self.assertTrue(scenario["id"])
            self.assertIn("expect", scenario)

    def test_ids_are_unique(self):
        ids = [s["id"] for s in self.scenarios]
        self.assertEqual(len(ids), len(set(ids)))

    def test_delivery_scenarios_name_a_receiver_suffix_and_a_timeout(self):
        for scenario in self.scenarios:
            if scenario.get("requires_tunnel"):
                self.assertIn("receiver_suffix", scenario)
                self.assertIn("timeout_s", scenario)
                self.assertIn("receiver_status", scenario["expect"])

    def test_registration_scenarios_name_a_url(self):
        for scenario in self.scenarios:
            if not scenario.get("requires_tunnel"):
                self.assertIn("registration_url", scenario)

    def test_no_scenario_uses_a_wildcard_host(self):
        for scenario in self.scenarios:
            url = scenario.get("registration_url", "")
            self.assertNotIn("*", url)


class RunnerHelpersTest(unittest.TestCase):

    def test_register_secret_keeps_earlier_entries(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "secrets.json"
            path.write_text("{}", encoding="utf-8")
            runner.register_secret(path, "/s/a", "whsec_a")
            runner.register_secret(path, "/s/b", "whsec_b")
            self.assertEqual({"/s/a": "whsec_a", "/s/b": "whsec_b"}, json.loads(path.read_text(encoding="utf-8")))

    def test_receiver_requests_filters_by_path(self):
        with tempfile.TemporaryDirectory() as directory:
            log = Path(directory) / "requests.jsonl"
            log.write_text(json.dumps({"path": "/s/a"}) + "\n" + json.dumps({"path": "/s/b"}) + "\n",
                           encoding="utf-8")
            self.assertEqual(["/s/a"], [r["path"] for r in runner.receiver_requests(log, "/s/a")])

    def test_check_shape(self):
        self.assertEqual({"name": "x", "passed": True, "detail": 1}, runner.check("x", 1, 1))


if __name__ == "__main__":
    unittest.main()
