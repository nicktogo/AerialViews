# Screensaver Fetch Mode

## Problem

The app displays overlay text on the screensaver (Creem business metrics). Currently, a Mac launchd service pushes text to the TV's Ktor API at 6am daily. This has two drawbacks:

1. The Mac must know the TV's IP and actively push — if the TV is off or unreachable, the push fails silently.
2. Data only refreshes once per day regardless of whether the screensaver is active.

## Solution

Flip to a pull model: the screensaver fetches text from a configurable HTTP endpoint when it is active, on a configurable interval.

Two changes:
- **Mac side:** Add a `--serve` mode to the existing `creem-screensaver.py` script that runs a persistent HTTP server serving pre-formatted slot data.
- **TV side:** Add a `MessageFetchService` that periodically fetches from the configured URL while the screensaver is running.

## Architecture

```
┌──────────────┐   GET /metrics    ┌──────────────────┐
│  Android TV  │ ◄──────────────── │  Mac HTTP Server  │
│  (AerialViews│   JSON response   │  (creem-screensaver│
│   screensaver)│                  │   --serve)         │
└──────────────┘                   └──────────────────┘
       │                                    │
       │ fetch on start,                    │ background thread
       │ repeat every N min                 │ refreshes Creem API
       │ while screensaver active           │ every M min
       │                                    │
       ▼                                    ▼
  MessageEvent →                      Cached JSON slots
  overlay rendering
```

## TV Side

### New Prefs (GeneralPrefs)

- `messageFetchUrl: String` — URL to fetch from. Empty = disabled. Pref key: `message_fetch_url`.
- `messageFetchIntervalMinutes: String` — fetch interval in minutes. Default `"60"`. Pref key: `message_fetch_interval_minutes`.

No separate "enabled" toggle — a non-empty URL means fetch mode is active.

### New KtorServer Routes

**`POST /fetch-config`** — configure fetch mode.

Request body:
```json
{
  "url": "http://192.168.1.50:8082/metrics",
  "intervalMinutes": 60
}
```

- `url` (required): the endpoint to fetch. Empty string disables fetch mode.
- `intervalMinutes` (optional): fetch interval, defaults to 60.

Response: `{"success": true, "message": "Fetch config updated"}`

**`GET /fetch-config`** — return current config.

Response:
```json
{
  "url": "http://192.168.1.50:8082/metrics",
  "intervalMinutes": 60
}
```

### New MessageFetchService Class

Location: `app/src/main/java/com/neilturner/aerialviews/services/MessageFetchService.kt`

Responsibilities:
- Reads `messageFetchUrl` and `messageFetchIntervalMinutes` from GeneralPrefs.
- Runs a coroutine loop: fetch immediately on `start()`, then `delay(intervalMinutes)`, repeat.
- On successful fetch, parses the JSON response and for each slot, posts a `MessageEvent` via `GlobalBus.post()` and persists to `GeneralPrefs.messageLine{N}`.
- On failure (network error, parse error), logs via Timber and waits for next cycle.
- `stop()` cancels the coroutine scope.

Expected JSON response from the server:
```json
{
  "slots": [
    {"slot": 1, "text": "MRR $50.00 · ARR $600", "textSize": 21, "textWeight": 400},
    {"slot": 2, "text": "Revenue $1,200 · 24 sales", "textSize": 21, "textWeight": 400},
    {"slot": 3, "text": "12 active · 18 customers", "textSize": 21, "textWeight": 400},
    {"slot": 4, "text": "Last: $29.00 · 3h ago", "textSize": 21, "textWeight": 400}
  ]
}
```

Fields per slot:
- `slot` (int, 1-4): which message slot to update
- `text` (string): the display text
- `textSize` (int, optional): font size, uses app default if omitted
- `textWeight` (int, optional): font weight, uses app default if omitted

### ScreenController Integration

In `init`, after the KtorServer setup block:
- If `GeneralPrefs.messageFetchUrl` is non-empty, create and start `MessageFetchService`.
- The `onMessageReceived` callback from the fetch service follows the same path as the existing KtorServer push handler: `GlobalBus.post(messageEvent)` + persist to `GeneralPrefs.messageLine{N}`.

In `stop()`:
- Call `messageFetchService?.stop()`.

### Push vs Fetch Interaction

Both push (`POST /message/{n}`) and fetch paths converge on `MessageEvent` + prefs persistence. The last write wins. In practice, users configure one or the other. No conflict resolution needed.

## Mac Side

### Serve Mode for creem-screensaver.py

New CLI flags:
- `--serve`: enter serve mode (mutually exclusive with `--tv-ip`)
- `--serve-port PORT`: HTTP port, default 8082
- `--refresh MINUTES`: how often to re-fetch from Creem API, default 30

Behavior:
1. On startup, fetch Creem metrics and cache the formatted slots JSON in memory.
2. Start a background thread that re-fetches every `--refresh` minutes.
3. Start `http.server.HTTPServer` on `--serve-port`.
4. `GET /metrics` returns the cached slots JSON (200), or 503 if cache not yet populated.
5. `GET /health` returns 200 with `{"status": "ok"}`.

Uses Python stdlib only (`http.server`, `threading`, `json`) — no new dependencies.

### Launchd Plist

Change from one-shot at 6am to a kept-alive process:
```xml
<key>ProgramArguments</key>
<array>
  <string>python3</string>
  <string>/path/to/creem-screensaver.py</string>
  <string>--serve</string>
  <string>--serve-port</string>
  <string>8082</string>
  <string>--refresh</string>
  <string>30</string>
</array>
<key>KeepAlive</key>
<true/>
```

### Backwards Compatibility

Existing push mode (`--tv-ip`, `--interval`, `--clear`) is unchanged. The `--serve` flag activates a separate code path. Both modes can coexist in the same script.

## Setup Flow

One-time setup after deploying both sides:

1. Start the Mac serve process: `python3 creem-screensaver.py --serve --serve-port 8082`
2. Push fetch config to the TV:
   ```bash
   curl -X POST http://<tv-ip>:8081/fetch-config \
     -H "Content-Type: application/json" \
     -d '{"url": "http://<mac-ip>:8082/metrics", "intervalMinutes": 60}'
   ```
3. The screensaver will fetch on its next activation.

## Files to Create/Modify

### New Files
- `app/src/main/java/com/neilturner/aerialviews/services/MessageFetchService.kt`

### Modified Files
- `app/src/main/java/com/neilturner/aerialviews/models/prefs/GeneralPrefs.kt` — add `messageFetchUrl`, `messageFetchIntervalMinutes`
- `app/src/main/java/com/neilturner/aerialviews/services/KtorServer.kt` — add `POST /fetch-config`, `GET /fetch-config` routes and request/response models
- `app/src/main/java/com/neilturner/aerialviews/ui/core/ScreenController.kt` — start/stop MessageFetchService
- `follow-trump-truth/scripts/creem-screensaver.py` — add `--serve` mode
