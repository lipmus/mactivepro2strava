# MActivePro to Strava

Sync workouts from **MActivePro / Microwear smartwatches** (Realtek LE7 chipset, e.g. Watch Ultra3) straight to **Strava**.

No root, no Shizuku, no Health Connect, no PC required at runtime.

---

## Why this exists

MActivePro (`com.njj.mactivepro`) has no Strava integration and no public API. Two approaches were tried first and both failed:

| Approach | Why it failed |
|---|---|
| Read MActivePro's log files directly | Android 11+ scoped storage blocks third-party apps from `/sdcard/Android/data/<other-app>/`, even with `MANAGE_EXTERNAL_STORAGE` |
| Patch the MActivePro APK to add Strava upload | The APK is packed with **Qihoo 360 Jiagu** (`assets/libjiagu_a64.so`), which detects tampering and refuses to run |

**What works:** MActivePro itself uploads every workout to the Microwear cloud at `http://api.microwear.com/api/`. That cloud endpoint is reachable from anywhere, so this project reads from it instead of from the phone's filesystem.

---

## How it works

```
Watch  ──BLE──▶  MActivePro  ──▶  api.microwear.com  ──▶  MActive Strava Sync  ──▶  Strava
                                       (cloud)                (this app)
```

1. Finish your workout; open MActivePro once so the watch syncs to the cloud.
2. Open **MActive Strava Sync** and tap **SYNC KE STRAVA SEKARANG**, or enable the 1-hour background sync.
3. The app pulls the workout from the Microwear cloud, converts it to **TCX**, and uploads it to Strava.

TCX is used rather than GPX because Strava only reads heart rate from TCX's `<HeartRateBpm>` element; the `<gpxtpx:hr>` GPX extension is silently dropped on upload.

---

## Repository layout

```
app/                       Android app (Kotlin, no third-party UI framework)
  MainActivity.kt          dashboard: sync button, auto-sync toggle, live log
  SettingsActivity.kt      editable credentials for other users
  SyncConfig.kt            SharedPreferences storage
  MicrowearClient.kt       Microwear cloud API client
  StravaClient.kt          Strava OAuth refresh + TCX upload
  TcxBuilder.kt            workout -> TCX conversion
  SyncWorker.kt            WorkManager background sync (1 h)
cli/sync.py                standalone Python CLI (stdlib only) that does the same
```

---

## Building

Requires JDK 17+ and the Android SDK (`ANDROID_HOME` set).

```bash
./gradlew :app:assembleRelease
```

The unsigned APK lands in `app/build/outputs/apk/release/`. Sign it before installing:

```bash
apksigner sign --ks ~/.android/debug.keystore --ks-pass pass:android \
  --out mactive-strava-sync.apk \
  app/build/outputs/apk/release/app-release-unsigned.apk
```

---

## Setup

You need four values. Get them once, then enter them in the app's **Pengaturan** screen.

### 1. Strava API application

1. Go to <https://www.strava.com/settings/api> and create an application.
   * **Authorization Callback Domain:** `localhost`
2. Note the **Client ID** and **Client Secret**.

### 2. Strava refresh token

The access token expires every 6 hours, but the **refresh token does not expire** — the app renews silently, so you only authorize once.

Run a local server to catch the OAuth redirect:

```bash
python -c "
from http.server import HTTPServer, BaseHTTPRequestHandler
import urllib.parse, urllib.request, json

class H(BaseHTTPRequestHandler):
    def do_GET(self):
        code = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)['code'][0]
        data = urllib.parse.urlencode({
            'client_id':'YOUR_CLIENT_ID', 'client_secret':'YOUR_CLIENT_SECRET',
            'code':code, 'grant_type':'authorization_code'}).encode()
        r = urllib.request.urlopen(urllib.request.Request('https://www.strava.com/oauth/token', data=data, method='POST'))
        print(json.dumps(json.loads(r.read().decode()), indent=2))
        self.send_response(200); self.end_headers(); self.wfile.write(b'OK')

HTTPServer(('127.0.0.1', 8080), H).handle_request()
"
```

Then open:

```
https://www.strava.com/oauth/authorize?client_id=YOUR_CLIENT_ID&response_type=code&redirect_uri=http://localhost:8080&approval_prompt=force&scope=activity:write,activity:read_all
```

Click **Authorize**. Copy the `refresh_token` from the JSON that prints.

### 3. Microwear TokenID

MActivePro sends a `tokenID` with every cloud request. Find it in the app's log:

```bash
adb pull /sdcard/Android/data/com.njj.mactivepro/files/njjLog/
grep -o '"tokenID":"[A-F0-9]*"' logs_*.txt | head -1
```

### 4. Watch MAC address

Shown in MActivePro under the device info, or in the same log:

```bash
grep -o 'mac=[0-9A-F:]*' logs_*.txt | head -1
```

### Enabling heart rate on Strava

Strava hides heart rate from third-party uploads until you opt in:

**Strava → Settings → Data Permissions → Health-Related Data → allow**

---

## Usage

### Android app

| Action | Result |
|---|---|
| **SYNC KE STRAVA SEKARANG** | Pulls the last 30 days and uploads anything not already on Strava |
| **Sync Otomatis Tiap 1 Jam** | WorkManager repeats the sync hourly in the background |
| **Pengaturan** | Change all four credentials — for sharing with another watch/user |

### CLI

```bash
cd cli
cp config.example.json config.json    # fill in your values
python sync.py --list                 # show cloud workouts
python sync.py --sync                 # upload new ones
python sync.py --sync --days 7        # last 7 days only
```

---

## Limitations

* **Only workouts with a GPS track are uploaded.** The watch records many sessions without GPS (1–2 km "indoor" entries with zero coordinates). Strava needs coordinates to build a route, so those are skipped. Raise or lower the guard in `TcxBuilder.build` / `build_tcx` if you want them anyway.
* **Heart rate is sparse.** The watch records roughly one HR sample per minute, so a 25-minute run yields ~25 readings stretched across ~750 trackpoints. Strava shows the average and max correctly, but the HR graph is coarse.
* **The watch's own GPS drops out for most sessions.** Only 2 of 15 sessions in testing had a usable route; this is a hardware/firmware behaviour, not a bug in this app.
* **The Microwear cloud is unofficial.** It is the vendor's own endpoint, used here as a data source. It can change without notice; if it stops returning data, the endpoint or payload may need updating.
* **Sport type comes from the watch and is not always right.** Microwear's `type: 142` maps to cycling in this app, but the watch also uses `142` for outdoor runs. Edit `SPORT_MAPPING` if your sessions land in the wrong Strava sport.
* **The watched workout must reach the cloud first.** If you never open MActivePro after a workout, it stays on the watch and this app cannot see it.

---

## License

MIT
