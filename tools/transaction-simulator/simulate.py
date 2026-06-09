#!/usr/bin/env python3
"""TrustLedger transaction simulator.

This script generates payloads for low-risk and high-risk transfer scenarios. When an API
URL is provided, it sends requests to the TrustLedger backend. Without an API URL, it prints
example payloads for demos and documentation.
"""
import argparse
import json
import time
import uuid
from urllib import request, error

SCENARIOS = {
    "low-risk": {"amount": "25.00", "reference": "Lunch", "deviceId": "trusted-device", "expected": "COMPLETED"},
    "new-beneficiary": {"amount": "400.00", "reference": "Urgent", "deviceId": "new-device", "expected": "HELD_FOR_REVIEW"},
    "large-transfer": {"amount": "4500.00", "reference": "Equipment", "deviceId": "new-device", "expected": "HOLD_OR_REJECT"},
    "velocity": {"amount": "75.00", "reference": "Velocity test", "deviceId": "trusted-device", "expected": "STEP_UP_OR_HOLD"},
}

def payload(scenario: str) -> dict:
    data = SCENARIOS[scenario]
    return {
        "sourceAccountId": "acc_demo_sender",
        "beneficiaryId": "ben_demo_receiver",
        "amount": data["amount"],
        "currency": "GBP",
        "reference": data["reference"],
        "deviceId": data["deviceId"],
        "scenario": scenario,
    }

def send(api_url: str, scenario: str) -> None:
    body = json.dumps(payload(scenario)).encode()
    idem = f"sim-{scenario}-{uuid.uuid4()}"
    req = request.Request(
        f"{api_url.rstrip('/')}/api/v1/transfers",
        data=body,
        method="POST",
        headers={"Content-Type": "application/json", "Idempotency-Key": idem},
    )
    try:
        with request.urlopen(req, timeout=10) as resp:
            print(resp.status, resp.read().decode())
    except error.URLError as exc:
        raise SystemExit(f"request failed: {exc}")

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--scenario", choices=SCENARIOS.keys(), default="low-risk")
    parser.add_argument("--api-url", help="TrustLedger API base URL, e.g. http://localhost:8080")
    parser.add_argument("--repeat", type=int, default=1)
    args = parser.parse_args()

    if args.api_url:
        for _ in range(args.repeat):
            send(args.api_url, args.scenario)
            time.sleep(0.2)
    else:
        print(json.dumps(payload(args.scenario), indent=2))
