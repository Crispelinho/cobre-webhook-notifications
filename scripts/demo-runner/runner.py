"""Runs the demo scenarios against a running service, in parallel, and checks each outcome.

Each scenario gets its own subscription path and its own event_type, so one scenario's events never reach another
scenario's subscription. The runner checks the service through its public API, reads the receiver's request log, and
deactivates the subscriptions it created at the end. It never changes the destination policy.

Requirements: a service started with WEBHOOK_ALLOWED_HOSTS set to the tunnel host (for delivery scenarios), a receiver
started with receiver.py using the same --secrets-file and --log-file, and the CLIENT_TOKEN and PRODUCER_TOKEN
environment variables.
"""

import argparse
import json
import os
import sys
import threading
import time
import uuid
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from urllib import error, request

TERMINAL = {"completed", "failed"}
SECRETS_LOCK = threading.Lock()
HERE = Path(__file__).resolve().parent


def api(base, method, path, token, body=None):
    """Return (status, parsed body). Error responses are returned, not raised, so checks can assert on them."""
    data = json.dumps(body).encode("utf-8") if body is not None else None
    req = request.Request(base + path, data=data, method=method,
                          headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"})
    try:
        with request.urlopen(req, timeout=30) as response:
            raw = response.read()
            return response.status, json.loads(raw) if raw else None
    except error.HTTPError as ex:
        raw = ex.read()
        try:
            return ex.code, json.loads(raw) if raw else None
        except json.JSONDecodeError:
            return ex.code, raw.decode("utf-8", errors="replace")


def check(name, passed, detail=None):
    return {"name": name, "passed": bool(passed), "detail": detail}


def register_secret(secrets_path, request_path, secret):
    """The receiver reads this file on every request, so the secret must be there before the first delivery."""
    with SECRETS_LOCK:
        current = json.loads(secrets_path.read_text(encoding="utf-8"))
        current[request_path] = secret
        secrets_path.write_text(json.dumps(current), encoding="utf-8")


def receiver_requests(log_path, request_path):
    if not log_path.exists():
        return []
    records = [json.loads(line) for line in log_path.read_text(encoding="utf-8").splitlines() if line]
    return [record for record in records if record["path"] == request_path]


def wait_terminal(ctx, notification_id, timeout_s):
    deadline = time.monotonic() + timeout_s
    last = {"delivery_status": "timeout", "attempts": []}
    while True:
        status, body = api(ctx["base"], "GET", f"/notification_events/{notification_id}", ctx["client_token"])
        if status == 200:
            last = body
            if body["delivery_status"] in TERMINAL:
                return body
        if time.monotonic() > deadline:
            return last
        time.sleep(ctx["poll_s"])


def run_delivery(ctx, scenario):
    checks = []
    expect = scenario["expect"]
    event_type = f"demo_{scenario['id'].replace('-', '_')}_{ctx['run']}"
    request_path = f"/s/{ctx['run']}/{scenario['id']}{scenario['receiver_suffix']}"
    subscription_id = None
    try:
        status, body = api(ctx["base"], "POST", "/subscriptions", ctx["client_token"],
                           {"url": f"https://{ctx['tunnel']}{request_path}", "event_types": [event_type]})
        checks.append(check("subscription created (201)", status == 201, status))
        if status != 201:
            return {"id": scenario["id"], "checks": checks}
        subscription_id = body["subscription_id"]
        register_secret(ctx["secrets_path"], request_path, body["secret"])

        status, body = api(ctx["base"], "POST", "/events", ctx["producer_token"], {
            "event_id": f"run-{ctx['run']}-{scenario['id']}",
            "client_id": "CLIENT001",
            "event_type": event_type,
            "content": json.dumps({"scenario": scenario["id"], "run": ctx["run"]}),
        })
        checks.append(check("event accepted (202)", status == 202, status))
        if status != 202:
            return {"id": scenario["id"], "checks": checks}
        ids = body["notification_event_ids"]
        checks.append(check("exactly one notification for this subscription", len(ids) == 1, ids))

        detail = wait_terminal(ctx, ids[0], scenario["timeout_s"])
        checks.append(check("delivery_status", detail["delivery_status"] == expect["delivery_status"],
                            detail["delivery_status"]))
        round_one = [attempt for attempt in detail["attempts"] if attempt["round"] == 1]
        checks.append(check("attempts in round 1", len(round_one) == expect["attempts"], len(round_one)))
        codes = sorted({attempt["http_status"] for attempt in round_one}, key=str)
        checks.append(check("attempt http_status", codes == [expect["receiver_status"]], codes))

        seen = receiver_requests(ctx["log_path"], request_path)
        checks.append(check("receiver saw every attempt", len(seen) == expect["attempts"], len(seen)))
        checks.append(check("signature valid on every request",
                            bool(seen) and all(r["signature_valid"] for r in seen),
                            [r["signature_valid"] for r in seen]))
    finally:
        if subscription_id is not None:
            status, _ = api(ctx["base"], "PATCH", f"/subscriptions/{subscription_id}", ctx["client_token"],
                            {"active": False})
            checks.append(check("test subscription deactivated", status == 200, status))
    return {"id": scenario["id"], "checks": checks}


def run_registration(ctx, scenario):
    expect = scenario["expect"]
    status, body = api(ctx["base"], "POST", "/subscriptions", ctx["client_token"], {
        "url": scenario["registration_url"],
        "event_types": [f"demo_registration_{ctx['run']}"],
    })
    checks = [check("registration refused", status == expect["registration_status"], status)]
    checks.append(check("generic message, no reason", status == 400 and "allowed destination" in json.dumps(body),
                        body))
    return {"id": scenario["id"], "checks": checks}


def run_scenario(ctx, scenario):
    try:
        if scenario.get("requires_tunnel"):
            result = run_delivery(ctx, scenario)
        else:
            result = run_registration(ctx, scenario)
    except Exception as ex:  # a broken scenario must not hide the others
        result = {"id": scenario["id"], "checks": [check("scenario ran without error", False, repr(ex))]}
    result["passed"] = all(item["passed"] for item in result["checks"])
    return result


def print_report(results):
    for result in results:
        print(f"[{'PASS' if result['passed'] else 'FAIL'}] {result['id']}")
        for item in result["checks"]:
            mark = "ok " if item["passed"] else "BAD"
            print(f"    {mark} {item['name']}: {item['detail']}")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--base-url", default="http://localhost:8081")
    parser.add_argument("--tunnel-host", help="public host only (no scheme, no port), for delivery scenarios")
    parser.add_argument("--scenarios", default=str(HERE / "scenarios.json"))
    parser.add_argument("--only", nargs="*", help="run only these scenario ids")
    parser.add_argument("--concurrency", type=int, default=3)
    parser.add_argument("--poll-seconds", type=float, default=10.0)
    parser.add_argument("--secrets-file", required=True, help="must be the file the receiver reads")
    parser.add_argument("--log-file", required=True, help="must be the log the receiver writes")
    parser.add_argument("--report", help="write the results as JSON to this path")
    args = parser.parse_args()

    client_token = os.environ.get("CLIENT_TOKEN")
    producer_token = os.environ.get("PRODUCER_TOKEN")
    if not client_token or not producer_token:
        sys.exit("CLIENT_TOKEN and PRODUCER_TOKEN must be set (see docs/process/local-https-delivery-runbook.md)")

    scenarios = json.loads(Path(args.scenarios).read_text(encoding="utf-8"))
    selected = [s for s in scenarios if not args.only or s["id"] in args.only]
    needs_tunnel = [s["id"] for s in selected if s.get("requires_tunnel")]
    if needs_tunnel and not args.tunnel_host:
        sys.exit(f"--tunnel-host is required for: {', '.join(needs_tunnel)}")

    secrets_path = Path(args.secrets_file)
    secrets_path.write_text("{}", encoding="utf-8")
    ctx = {
        "base": args.base_url.rstrip("/"),
        "tunnel": args.tunnel_host,
        "client_token": client_token,
        "producer_token": producer_token,
        "secrets_path": secrets_path,
        "log_path": Path(args.log_file),
        "poll_s": args.poll_seconds,
        "run": uuid.uuid4().hex[:8],
    }

    with ThreadPoolExecutor(max_workers=args.concurrency) as pool:
        results = list(pool.map(lambda s: run_scenario(ctx, s), selected))

    print_report(results)
    if args.report:
        Path(args.report).write_text(json.dumps(results, indent=2), encoding="utf-8")
    sys.exit(0 if all(result["passed"] for result in results) else 1)


if __name__ == "__main__":
    main()
