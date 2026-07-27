#!/usr/bin/env python3
"""
Phase 0 - prove the Amsterdam permit API works before writing any Android code.

Setup:
    pip install requests
    export PERMIT_USER=xxxxx
    export PERMIT_PASS=xxxx
    export CAR_WASIL=XX-123-Y
    export CAR_BROTHER=YY-456-Z

Usage:
    python permit.py status      # who currently holds the permit
    python permit.py wasil       # switch to your car
    python permit.py brother     # switch to your brother's car
"""

import os
import sys
import requests

BASE = "https://api.parkeervergunningen.egisparkingservices.nl/api"
PRODUCT_ID = 5807976

STATE_URL = f"{BASE}/v1/client_product/{PRODUCT_ID}"

# Only the two plates in active use. The third plate on the permit
# belongs to an inactive vehicle and is deliberately not selectable.
CARS = {
    "wasil": os.environ.get("CAR_WASIL"),
    "brother": os.environ.get("CAR_BROTHER"),
}


HEADERS = {
    "accept": "application/json, text/plain, */*",
    "content-type": "application/json",
    "origin": "https://parkeervergunningen.amsterdam.nl",
    "referer": "https://parkeervergunningen.amsterdam.nl/",
    "user-agent": (
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
        "(KHTML, like Gecko) Chrome/150.0.0.0 Safari/537.36"
    ),
}


def login():
    r = requests.post(
        f"{BASE}/ssp/login_check",
        json={
            "username": os.environ["PERMIT_USER"],
            "password": os.environ["PERMIT_PASS"],
        },
        headers=HEADERS,
        timeout=15,
    )
    r.raise_for_status()
    return r.json()["token"]


def get_plates(token):
    """Returns [(vrn, is_active), ...] for every plate on the permit."""
    r = requests.get(
        STATE_URL,
        headers={**HEADERS, "Authorization": f"Bearer {token}"},
        timeout=15,
    )
    r.raise_for_status()
    return [(v["vrn"], v["has_parking_session"]) for v in r.json()["vrns"]]


def active_plate(token):
    for vrn, is_active in get_plates(token):
        if is_active:
            return vrn
    return None


def activate(token, vrn):
    r = requests.post(
        f"{BASE}/v1/ssp/parking_session/activate",
        headers={**HEADERS, "Authorization": f"Bearer {token}"},
        json={"client_product_id": PRODUCT_ID, "vrn": vrn},
        timeout=15,
    )
    r.raise_for_status()
    return r.json()["parking_session_id"]


def main():
    if len(sys.argv) != 2 or sys.argv[1] not in ("status", *CARS):
        print(__doc__)
        sys.exit(1)

    cmd = sys.argv[1]
    token = login()

    if cmd == "status":
        for vrn, is_active in get_plates(token):
            print(f"  {vrn}  {'<-- ACTIVE' if is_active else ''}")
        return

    target = CARS[cmd]
    if not target:
        sys.exit(f"No plate configured for '{cmd}' - set the env var first.")

    current = active_plate(token)
    if current == target:
        print(f"{target} already holds the permit. Nothing to do.")
        return

    print(f"Currently active: {current or 'none'}")
    if input(f"Switch to {target}? [y/N] ").strip().lower() != "y":
        sys.exit("Cancelled.")

    session_id = activate(token, target)
    print(f"Activated, session {session_id}. Verifying...")

    # Never trust the write - read it back.
    confirmed = active_plate(token)
    if confirmed == target:
        print(f"Confirmed: {confirmed} holds the permit.")
    else:
        sys.exit(f"MISMATCH - server reports {confirmed}. Check the website.")


if __name__ == "__main__":
    main()
