"""MActivePro to Strava Sync CLI
Standalone script for syncing MActivePro workouts to Strava.
"""
from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
TOKENS_PATH = os.path.join(HERE, "strava_tokens.json")
STATE_PATH = os.path.join(HERE, "uploaded.json")
CONFIG_PATH = os.path.join(HERE, "config.json")

MICROWEAR_URL = "http://api.microwear.com/api/"

SPORT_MAPPING = {
    1: ("Running", "run", "Lari"),
    2: ("Running", "walk", "Jalan Kaki"),
    3: ("Biking", "ride", "Bersepeda"),
    4: ("Running", "hike", "Hiking"),
    10: ("Other", "workout", "Latihan"),
    25: ("Other", "workout", "Workout"),
    140: ("Running", "run", "Lari"),
    141: ("Running", "walk", "Jalan Kaki"),
    142: ("Biking", "ride", "Bersepeda"),
    143: ("Biking", "ride", "Bersepeda"),
}


def load_json(path, default=None):
    try:
        with open(path, encoding="utf-8") as f:
            return json.load(f)
    except FileNotFoundError:
        return default


def save_json(path, data):
    tmp = path + ".tmp"
    with open(tmp, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=2)
    os.replace(tmp, path)


def refresh_strava_token(tokens, config):
    expires_at = tokens.get("expires_at", 0)
    now = dt.datetime.now(dt.timezone.utc).timestamp()
    if expires_at - now > 300:
        return tokens

    body = urllib.parse.urlencode({
        "client_id": config["client_id"],
        "client_secret": config["client_secret"],
        "grant_type": "refresh_token",
        "refresh_token": tokens["refresh_token"],
    }).encode()
    req = urllib.request.Request("https://www.strava.com/oauth/token", data=body, method="POST")
    with urllib.request.urlopen(req, timeout=30) as resp:
        fresh = json.loads(resp.read().decode())
    fresh.setdefault("refresh_token", tokens["refresh_token"])
    save_json(TOKENS_PATH, fresh)
    print(f"  Token Strava diperbarui (berlaku s.d. {dt.datetime.fromtimestamp(fresh['expires_at'])})")
    return fresh


def microwear_workouts(config, start_date, end_date):
    payload = {
        "deviceType": 100,
        "msgType": 0,
        "cmdID": 2202,
        "tokenID": config["microwear_token"],
        "data": {"updateTime": start_date, "endUpdateTime": end_date, "source": "0"},
        "osType": 4,
        "lang": "en",
        "version": "2.0.2",
        "mac": config["watch_mac"],
    }
    req = urllib.request.Request(
        MICROWEAR_URL,
        data=json.dumps(payload).encode(),
        headers={"Content-Type": "application/json", "User-Agent": "okhttp/4.12.0"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=60) as resp:
        body = json.loads(resp.read().decode())

    if not body.get("success"):
        print(f"  Microwear cloud menolak: {body.get('messsage')}")
        return []
    return body.get("data", []) or []


def build_tcx(workout, tcx_sport):
    coords = [p.split("|") for p in (workout.get("coordinateArr") or "").split(",") if "|" in p]
    if not coords:
        return None

    raw_dt = workout.get("dateTime")
    if raw_dt:
        start_local = dt.datetime.strptime(raw_dt, "%Y-%m-%d %H:%M:%S").replace(
            tzinfo=dt.timezone(dt.timedelta(hours=7))
        )
    else:
        start_local = dt.datetime.fromtimestamp(int(workout["timeStamp"]), dt.timezone.utc)
    start_utc = start_local.astimezone(dt.timezone.utc)
    start_iso = start_utc.strftime("%Y-%m-%dT%H:%M:%SZ")

    duration = int(workout.get("duration") or len(coords))
    distance = float(workout.get("distance") or 0)
    calories = int(float(workout.get("calories") or 0))
    hrs = [int(h) for h in (workout.get("heartRateArr") or "").split(",") if h.strip().isdigit() and 25 <= int(h) <= 240]
    step = duration / max(1, len(coords) - 1)

    lines = [
        '<?xml version="1.0" encoding="UTF-8"?>',
        '<TrainingCenterDatabase xmlns="http://www.garmin.com/xmlschemas/TrainingCenterDatabase/v2">',
        "  <Activities>",
        f'    <Activity Sport="{tcx_sport}">',
        f"      <Id>{start_iso}</Id>",
        f'      <Lap StartTime="{start_iso}">',
        f"        <TotalTimeSeconds>{duration}</TotalTimeSeconds>",
        f"        <DistanceMeters>{distance:.1f}</DistanceMeters>",
        f"        <Calories>{calories}</Calories>",
        "        <Intensity>Active</Intensity>",
        "        <TriggerMethod>Manual</TriggerMethod>",
        "        <Track>",
    ]
    for i, (lat, lon) in enumerate(coords):
        when = (start_utc + dt.timedelta(seconds=i * step)).strftime("%Y-%m-%dT%H:%M:%SZ")
        lines.append("          <Trackpoint>")
        lines.append(f"            <Time>{when}</Time>")
        lines.append("            <Position>")
        lines.append(f"              <LatitudeDegrees>{lat}</LatitudeDegrees>")
        lines.append(f"              <LongitudeDegrees>{lon}</LongitudeDegrees>")
        lines.append("            </Position>")
        if hrs:
            hr = hrs[min(len(hrs) - 1, i * len(hrs) // len(coords))]
            lines.append("            <HeartRateBpm>")
            lines.append(f"              <Value>{hr}</Value>")
            lines.append("            </HeartRateBpm>")
        lines.append("          </Trackpoint>")
    lines += [
        "        </Track>",
        "      </Lap>",
        "    </Activity>",
        "  </Activities>",
        "</TrainingCenterDatabase>",
    ]
    return "\n".join(lines).encode("utf-8")


def upload_to_strava(token, tcx_bytes, name, desc, act_type, ext_id):
    boundary = "----JoyoFitTCXBoundary7d3a"
    parts = []

    def field(n, v):
        parts.append(f"--{boundary}\r\nContent-Disposition: form-data; name=\"{n}\"\r\n\r\n{v}\r\n".encode())

    field("data_type", "tcx")
    field("name", name)
    field("description", desc)
    field("activity_type", act_type)
    field("external_id", ext_id)
    parts.append(
        f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; filename=\"activity.tcx\"\r\n"
        f"Content-Type: application/vnd.garmin.tcx+xml\r\n\r\n".encode()
        + tcx_bytes
        + b"\r\n"
    )
    parts.append(f"--{boundary}--\r\n".encode())

    req = urllib.request.Request(
        "https://www.strava.com/api/v3/uploads",
        data=b"".join(parts),
        headers={
            "Authorization": f"Bearer {token}",
            "Content-Type": f"multipart/form-data; boundary={boundary}",
        },
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=120) as resp:
        return json.loads(resp.read().decode())


def check_upload_status(token, upload_id):
    req = urllib.request.Request(
        f"https://www.strava.com/api/v3/uploads/{upload_id}",
        headers={"Authorization": f"Bearer {token}"},
    )
    with urllib.request.urlopen(req, timeout=30) as resp:
        return json.loads(resp.read().decode())


def main():
    ap = argparse.ArgumentParser(description="Sync Microwear workouts to Strava")
    ap.add_argument("--list", action="store_true", help="Lihat aktivitas di cloud")
    ap.add_argument("--sync", action="store_true", help="Upload aktivitas baru ke Strava")
    ap.add_argument("--days", type=int, default=30, help="Jumlah hari (default: 30)")
    args = ap.parse_args()

    if not (args.list or args.sync):
        ap.print_help()
        return 1

    config = load_json(CONFIG_PATH)
    if not config:
        print(f"Error: {CONFIG_PATH} tidak ditemukan.")
        return 1
    tokens = load_json(TOKENS_PATH)
    if not tokens:
        print(f"Error: {TOKENS_PATH} tidak ditemukan.")
        return 1

    today = dt.datetime.now(dt.timezone(dt.timedelta(hours=7))).date()
    start = today - dt.timedelta(days=args.days)
    workouts = microwear_workouts(config, str(start), str(today))
    print(f"Ditemukan {len(workouts)} sesi olahraga ({start} s.d. {today}).\n")

    for w in sorted(workouts, key=lambda x: x.get("dateTime") or ""):
        coords = w.get("coordinateArr") or ""
        pts = coords.count("|") + (1 if coords else 0)
        dist = (w.get("distance") or 0) / 1000.0
        dur = int(w.get("duration") or 0)
        print(f"  {w.get('dateTime')} | {dist:.2f} km | {dur//60}m{dur%60:02d}s | {pts} GPS pts")

    if args.list:
        return 0

    tokens = refresh_strava_token(tokens, config)
    token = tokens["access_token"]
    state = load_json(STATE_PATH, {}) or {}

    for w in sorted(workouts, key=lambda x: x.get("dateTime") or ""):
        coords = w.get("coordinateArr") or ""
        if coords.count("|") < 10:
            continue
        key = f"{w.get('timeStamp') or w.get('dateTime')}"
        if key in state:
            continue

        stype = int(w.get("type") or 1)
        tcx_sport, act_type, prefix = SPORT_MAPPING.get(stype, ("Running", "run", "Olahraga"))
        raw_dt = w.get("dateTime") or ""
        time_str = dt.datetime.strptime(raw_dt, "%Y-%m-%d %H:%M:%S").strftime("%d %b %H:%M") if raw_dt else ""
        name = f"{prefix} {time_str}"
        tcx = build_tcx(w, tcx_sport)
        if not tcx:
            continue

        print(f"\nMengunggah {name}...")
        try:
            res = upload_to_strava(token, tcx, name, "Synced via MActive Strava Sync", act_type, f"microwear-{key}")
            upload_id = res.get("id")
            st = {}
            for _ in range(25):
                time.sleep(2)
                st = check_upload_status(token, upload_id)
                if st.get("status") not in ("Your activity is still being processed.", None):
                    break
            if st.get("activity_id"):
                print(f"  BERHASIL: https://www.strava.com/activities/{st['activity_id']}")
                state[key] = st["activity_id"]
                save_json(STATE_PATH, state)
            elif "duplicate" in (st.get("error") or "").lower():
                # Already on Strava from an earlier run — treat as done, not a failure.
                print(f"  SUDAH ADA: {st['error']}")
                state[key] = 0
                save_json(STATE_PATH, state)
            else:
                print(f"  Response: {st.get('status')} {st.get('error') or ''}")
        except Exception as e:
            print(f"  Gagal: {e}")

    return 0


if __name__ == "__main__":
    sys.exit(main())
