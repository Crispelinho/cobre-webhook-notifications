"""Test receiver for the demo runner.

Answers 500 for paths ending in /fail and 200 for everything else. For each request it records one JSON line with the
path, the status it answered, the body length and whether the Cobre-Signature header verifies against the secret the
runner registered for that path. It does not change the delivery path: the service signs and sends normally.
"""

import argparse
import hashlib
import hmac
import json
import threading
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path


def verify(header, body, secret):
    """Return True when `header` (t=<unix>,v1=<hex>) signs `body` with `secret`."""
    if not header or secret is None:
        return False
    parts = dict(part.split("=", 1) for part in header.split(",") if "=" in part)
    timestamp, signature = parts.get("t"), parts.get("v1")
    if not timestamp or not signature:
        return False
    expected = hmac.new(secret.encode("utf-8"), timestamp.encode("utf-8") + b"." + body, hashlib.sha256).hexdigest()
    return hmac.compare_digest(signature, expected)


def status_for(path):
    return 500 if path.endswith("/fail") else 200


class Handler(BaseHTTPRequestHandler):
    secrets_file = None
    log_file = None
    lock = threading.Lock()

    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(length)
        status = status_for(self.path)
        secrets = json.loads(Path(self.secrets_file).read_text(encoding="utf-8")) if self.secrets_file else {}
        record = {
            "at": datetime.now(timezone.utc).isoformat(),
            "path": self.path,
            "status": status,
            "body_length": len(body),
            "signature_valid": verify(self.headers.get("Cobre-Signature", ""), body, secrets.get(self.path)),
        }
        with self.lock, open(self.log_file, "a", encoding="utf-8") as log:
            log.write(json.dumps(record) + "\n")
        self.send_response(status)
        self.end_headers()

    def log_message(self, format, *args):
        pass


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--port", type=int, default=9300, help="local port; keep it off 9200 if the tunnel script uses it")
    parser.add_argument("--secrets-file", required=True, help="JSON object: request path -> signing secret")
    parser.add_argument("--log-file", required=True, help="JSON lines, one per request")
    args = parser.parse_args()
    Handler.secrets_file = args.secrets_file
    Handler.log_file = args.log_file
    server = ThreadingHTTPServer(("127.0.0.1", args.port), Handler)
    print(f"receiver listening on 127.0.0.1:{args.port}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
