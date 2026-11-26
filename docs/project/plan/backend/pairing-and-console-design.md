# Pairing in the PC app, actions from the console, and the console dashboard

Status: **designed, not started.** Written 2026-11-26 on `backend-tests`.
Product record: [PRODUCT.md](../../../../PRODUCT.md). Design system:
[DESIGN.md](../../../../DESIGN.md).

Three changes that depend on each other:

1. **Pair this PC with a QR, from the app.** The engine — not the window — owns
   the device identity and the gateway socket.
2. **Send an action from the console.** Household → device → free-text action →
   status.
3. **Console as a dashboard.** One shell, one stylesheet, one icon.

Nothing here adds a second pairing implementation, a second gateway socket, or a
path that skips a device's approval.

---

## 1. Why

The PC app can only be paired by pasting a `hopa://pair?p=…` link that another
process printed: the QR flow exists in the PC's own engine, but no UI reaches it,
so the end-to-end demo needs a terminal and a copied string. The console can find
a household and list its devices but cannot ask a device to do anything, and its
pages have no stylesheet at all.

| Person | Action | Result |
|---|---|---|
| Support | opens the console, finds a household by number | devices with live status |
| Support | picks a device, types "open Settings and tell me the build number", sends | task id, state `queued → delivered → done`, no message text in the console |
| Owner | opens the PC app, Settings, "Pair this phone", scans the QR and confirms on the phone | app reads **Connected**, and stays connected after a restart |
| Owner | does nothing on the phone | a payment-class action still stops and asks |

**Anti-goals.** Two sockets or two identity stores on the PC. A "trusted mode"
that lets the console or an automation skip a device's approval for a
payment-class action (`CLAUDE.md`, golden rule 2). The console reading what a
device produced. A QR/X25519/claim implementation in C# that drifts from the
tested Kotlin one.

---

## 2. What exists (verified in the tree)

**PC pairing**

- `apps/windows/sidecar/…/protocol/handler/BackendHandler.kt` already handles
  `backend.pair {url, name}`: `BackendAdmission` mints, renders the QR, polls five
  minutes, claims, unwraps the household key, stores `backend-session.json` and
  starts `GatewayHost`.
- `BackendAdmission` writes the QR to **stderr only** (`out` sink, ASCII blocks
  plus `pair-qr.png`); `QrRender` has `matrix`, `blocks`, `writePng`.
- `Protocol.backendPair(ok, error)` does **not** echo the request `id`; it is the
  one request exempted in `ProtocolFamilyTest`
  (`withoutId = setOf("ready", "log", "session", "backend.pair")`). The window's
  `EngineTransport.ReadLoopAsync` drops a reply with no `id`, so the command is
  unusable from the window today.
- `PairingGate` refuses a second concurrent admission.
  `ProtocolMode#startStoredSession` restarts a stored session and the gateway at
  boot, so "connected" survives a restart with no new work.
- The window's half is separate: `GatewayPairing.cs` claims an operator link
  (`/v1/pair/claim`), `GatewayCredentials` stores `hopa.gateway` in Credential
  Manager, and `GatewayClient*` (~700 lines) owns its own socket. So today there
  are **two** identities and, if both run, **two** sockets.

**Console actions**

- `TaskRouter.createServerTask` refuses anything outside
  `DIAG_TYPES = diag.ping | diag.state | diag.reset`, caps the body at 32 KiB,
  audits `diag.send`, and takes tenancy from `DeviceDirectory.lookup`.
- `relay()` accepts only `task.ack | progress | result | error | cancel`; a new
  reply type is refused `E_UNSUPPORTED`. It already terminates a
  **server-authored** task (`done` / `failed`) and notes: "A server-authored task
  has no sending socket to relay the result to; the row and its event are the
  whole record."
- `admin/DiagSender.kt` is the shared sender behind `POST /console/device/{id}/diag`;
  its tenancy check is the lookup, answered `403 E_TENANT`.
- Clients already run delivered work. Phone: `RemoteTaskRunner.handle` reads
  `body["goal"]`, acks, claims the surface, runs the turn under the approval
  policy, answers `task.result`/`task.error`. PC:
  `sidecar/backend/BackendGateway.handleCreate` unseals `{"sealed": …}` and runs
  `EngineTaskRunner` (goal → answer).
- `GET /v1/devices` with the device bearer token returns the household's
  `DeviceView {id, platform, name, current, …}` — the engine can resolve "the
  phone" by platform without a new route.

**Two gaps the design must close**

- **Reply frames are dropped.** `BackendClient.handleFrame` routes
  `task.create → onTask` and `diag.* → onDiagnostic` and has **no case for
  `task.result` / `task.error` / `task.progress` / `task.ack`**. The window's C#
  client parses `task.result` itself, which is why phone approvals work today;
  retiring it (A1) removes that parser with nothing replacing it.
- **Reply bodies are plaintext and persisted.** `BackendReplies.result` puts the
  turn's answer in the `task.result` body, `progress` puts a note in
  `task.progress`, and `relay()` stores that body in `task_events`. Pre-existing
  for device-to-device runs; this plan keeps the new admin-action flow off that
  path rather than changing the old one.

**Console**

- `ConsoleViews.kt` (303 lines) emits bare `h1`/`table`/`form` via kotlinx.html:
  no stylesheet, no layout, no navigation.
- `ConsoleRoutes.kt` holds the admin cookie session, the login cross-site guard
  (`call.crossSite()`), reveal-on-demand PII audit (`admin.pii.reveal`), and
  account removal.
- `EventLog` is JSON on stdout only: there is no queryable activity feed, and
  this plan does not invent one.

---

## 3. Decisions

| # | Decision | Rejected alternative, and why |
|---|---|---|
| **A1** | The **engine** owns the PC identity and the gateway socket. The window is a view: it starts pairing, shows the QR, shows Connected. | A2 (window keeps the socket, engine hands over credentials): two stores, two sockets, and a console action delivered to the PC would land in a client that only logs it. |
| **B1** | The console shows an action's **state**, never its output; for the new `admin.action` flow the devices reply **status-only**, so a console action never puts device output in a server row. | B2 (answer text in the console): displays device output to support, and the reply body is what would carry it — B2 is a storage decision, not just a rendering one. |
| **C1** | Redesign **every** console page behind one shell. | C2 (only household/devices): sign-in and error pages would look orphaned. |

**The plaintext rule, stated honestly.** A console action is authored *by the
server*, so plaintext hides nothing the server did not write; it follows the
`diag.*` precedent — admin-only, audit-logged, capped. Device output is
different: replies are plaintext today and `relay()` persists them for
device-to-device runs. This plan inherits that exposure, does not widen it, and
does not fix it (§10). The new `admin.action` flow keeps device output out of
server rows entirely: both clients reply status-only.

**Approvals keep their order.** `ApprovalRouter` decides automation → phone →
person today. Moving the socket into the engine must not reorder that, so the
*policy* stays in the window and only the transport moves: the window asks the
engine to put the question to the phone (`gateway.approval.ask`) and denies on
timeout or failure exactly as it does now. Two prerequisites are tasks, not
assumptions: the core client must stop dropping reply frames (§4.2) and the
engine must know which device is the phone (§4.1).

---

## 4. Part 1 — pairing, engine-owned

### 4.1 Protocol

`backend.pair` becomes non-blocking; the result arrives as an event, because a
five-minute block cannot sit inside one request/reply.

| Message | Direction | Shape |
|---|---|---|
| `backend.pair` | window → engine | `{type, id, url, name}` |
| reply | engine → window | `{type: "backend.pair", id, accepted: true}` or `{accepted: false, error}` |
| `backend.qr` (event) | engine → window | `{link, png, fingerprint, expiresAt}` (`png` = base64 PNG) |
| `backend.pair.result` (event) | engine → window | `{ok, deviceId?, householdId?, error?}` |
| `backend.pair.cancel` | window → engine | `{type, id}` → `{cancelled: bool}` |
| `backend.status` (request, and an event on change) | both | `{paired, deviceId?, householdId?, online, phoneOnline, phoneDeviceId?}` |

`backend.pair` loses its `withoutId` exemption; `ProtocolFamilyTest` catches it
if it does not. `phoneDeviceId` is resolved engine-side — `BackendState.Live.peers`
intersected with `GET /v1/devices` filtered to `platform == "android"` — and
refreshed when peers change. Several phones online is a documented tiebreak
(first by name, logged); none means `phoneOnline: false` and no id. `peers` alone
is not enough: it lists every online household device, phones and PCs alike.

### 4.2 Engine tasks

- [ ] `QrRender.png(matrix): ByteArray` beside `writePng`; keep the file variant
      for `--pair-backend`.
- [ ] `BackendAdmission` gains a `qr: (QrPayload) -> Unit` seam (`link`,
      `png`, `fingerprint`, `expiresAt`); stderr output stays for the headless path.
- [ ] `BackendHandler`: reply immediately with the gate result, run the admission
      on the existing scope, emit `backend.qr` then `backend.pair.result`, always
      release `PairingGate`.
- [ ] `backend.pair.cancel`: cancel the running job, release the gate.
- [ ] `backend.status`: read `BackendSessionStore` + `BackendState`; emit on pair
      success, on `hello.ack`, and on disconnect. Reply from a coroutine if it
      waits (the dispatcher is serial).
- [ ] **Prerequisite, core:** `BackendClient.handleFrame` gains a reply seam —
      `task.ack | task.progress | task.result | task.error → dispatch(frame, onReply)`
      (new `var onReply` on `BackendLink`). Without it the phone's approval answer
      is dropped and every ask times out into a deny. `BackendDeliveriesTest`
      covers the new dispatch.
- [ ] `gateway.approval.ask {request, timeoutMs}` → `{allow, body?}`: send
      `task.needs_approval` to `phoneDeviceId`, await the relayed `task.result`
      matched by `task` id, deny on timeout. Runs on the backend scope so the
      stdin loop never blocks.
- [ ] `GatewayHost` / `BackendGateway` writes `config.setGatewayState(paired,
      phoneOnline)` from its own `BackendState` transitions: the window's
      `gateway.state` messages stop in this slice, and both `CapabilitiesCollector`
      and the config summary read these flags.

### 4.3 Window tasks

- [ ] `EngineTransport.IsEvent` += `backend.qr`, `backend.pair.result`,
      `backend.status`.
- [ ] `EngineHostClient`: `PairBackendAsync`, `CancelPairingAsync`,
      `BackendStatusAsync`; events `BackendQrReceived`, `BackendPairFinished`,
      `BackendStatusChanged`, `GatewayApprovalAnswered`.
- [ ] Settings → General: replace the link box and "Pair this PC" with a **Phone
      link** panel — `[Pair this phone]` → QR image + fingerprint + countdown +
      Cancel → **Connected** (device id, household id) — plus a failure state with
      the reason and Retry.
- [ ] `IApprovalPhone` reimplemented over `gateway.approval.ask`, so
      `ApprovalRouter` keeps its order and its deny-on-failure rule.
- [ ] Retire `Integrations/Gateway/{GatewayClient,GatewayClient.Requests,GatewayClient.Socket,GatewayCredentials,GatewayPairing,GatewayFrame,GatewayFrames,IApprovalPhone}.cs`,
      `EngineSession.Gateway`, `EngineHostClient.SendGatewayState`, and the
      `hopa.gateway` credential (delete once, log it). The replacement writer for
      the gateway flags is the engine task above, or `capabilities` reports
      `gatewayPaired=false` forever.
- [ ] One commit must both stop the window's socket and let the engine's run, so
      no build ever holds two.

---

## 5. Part 2 — send an action

### 5.1 Backend

- [ ] `TaskRouter.createServerAction(adminId, target, goal)`:
      type `admin.action`, body `{"goal": "…"}` in plaintext; goal trimmed,
      1…2 000 chars, frame body ≤ 32 KiB; tenancy from `DeviceDirectory.lookup`
      (same refusal as diag); state `delivered` when online, otherwise `queued` +
      wake; audit `admin.action.send` with ids only — never the text
      (`JsonEventLog` already drops `body`).
- [ ] `relay()` untouched: the device's `task.result` / `task.error` ends the
      task and there is no relay target.

### 5.2 Engine

- [ ] `BackendGateway.handle`: `frame.type == "admin.action"` → plaintext goal →
      `runner.run(goal)` → `task.result {plaintext: true}`; failure →
      `task.error`. Log frame type and task id only.
- [ ] The answer goes into the engine's records/conversation, so the person at
      the PC sees it there.

### 5.3 Phone

- [ ] `BackendClient.handleFrame`: `"admin.action" -> dispatch(frame, onTask)`
      (`core/backend`, one line plus tests). The runner path is unchanged: it
      already reads `body.goal`, acks, and runs under the approval policy.
- [ ] `RemoteTaskRunner` replies **status-only** for an `admin.action` delivery:
      the final `task.result` carries `{"plaintext": true}` with no summary, and
      `task.progress` notes are suppressed for this frame type.
      `BackendReplies.result(task, device, result.answer)` is the default and
      would put the person's answer text into `task_events` via `relay()`. The
      answer stays in the phone's run history and notification, as for a local run.

### 5.4 Console

- [ ] `POST /console/action` (`household`, `device`, `goal`): login cross-site
      guard, `400` bad uuid, `403 E_TENANT` device outside the household, `422`
      empty/oversized goal. The form disables its submit button on submit — every
      POST mints a new `messageId`, so a double-click runs the goal twice.
- [ ] Accepted page (`202`): task id, state, trace, target device, links back.
- [ ] `GET /console/action/{taskId}?household=…`: reads `TaskStore.find` and
      renders `queued | delivered | acked | progress | done | failed` with
      `<meta http-equiv="refresh" content="3">` while non-terminal. **Never a
      body.**

---

## 6. Part 3 — console as a dashboard

Operate surface: dense, calm, scan-first. `DESIGN.md` is the authoritative system;
this section records what the plan relies on.

### 6.1 Palette

Source: Octet Design Labs, "Client-focused dashboards" —
`#fdfdfe #f0f3f5 #d6d9df #bdc2c7 #8f9192 #3d766d`.

| Token | Value | Use |
|---|---|---|
| `--paper` | `#fdfdfe` | page background |
| `--surface` | `#f0f3f5` | cards, table head, rail |
| `--line` | `#d6d9df` | borders, dividers |
| `--muted` | `#bdc2c7` | offline dot, disabled, rules |
| `--accent` | `#3d766d` | online, primary button, focus ring |
| `--accent-ink` | `#2c5750` | accent text on light: hover, links |
| `--ink` | `#1f2422` | body text (derived) |
| `--ink-2` | `#5b615e` | secondary text (derived) |
| `--warn` | `#8a6320` | queued (derived) |
| `--danger` | `#8f3a30` | failed, danger zone (derived) |

`#8f9192` on `#fdfdfe` is ≈2.9:1, so it is **not** text: meta and disabled only.
The two derived inks exist so body and secondary text clear 4.5:1. Status is
always dot **and** word.

### 6.2 Type and rhythm

Segoe UI Variable with a system fallback; Cascadia Mono with tabular numerals for
ids, timestamps and counts. Scale 12 / 13 / 15 / 20 / 28, one 4px spacing scale,
44px table rows, prose to 68ch.

### 6.3 Shell and pages

Rail 208px on `--surface` (Overview, Households, Devices), active item in
`--accent` with a 2px inset marker; rail header carries the mark (§6.4) at 40px
and the words "HOPA console". Top bar: page title, version, DB state, admin email,
sign out. Under 900px the rail becomes a horizontal strip, the mark drops to
28px, and tables scroll rather than reflowing. No JavaScript, no framework, no
webfont.

| Page | Content |
|---|---|
| Sign in | centred card, one field pair, one primary button |
| Overview | four stat cards from real data only — devices total, online now, households, revoked — then the search field, then the six most recent devices |
| Households | search → household dashboard: identity card (reveal on demand), device table, **Send an action** panel, danger zone (typed last four) |
| Devices | the all-devices table: name/platform, status, last seen, household, masked phone + reveal, "Send action" |
| Device detail | Diagnostics (existing diag buttons) and Actions side by side |
| Action status | state, trace, target, auto-refresh while non-terminal |

```
HOPA console        version · db ok · admin@hopa.test · Sign out
──────────────────────────────────────────────────────────────
Overview    │  +972 •• 0088   household 4ec61974-7b51-4af0
Households  │  2 devices   1 online   queue 0
Devices     │
            │  ● 2506BPN68G   android   online    15:52:10Z
            │  ○ Windows PC   windows   offline   yesterday
            │  ┌ Send an action ──────────────────────────┐
            │  │ Device  [ 2506BPN68G · online         ▾ ]│
            │  │ Action  [ Open Settings and tell me the  ]│
            │  │         [ build number                   ]│
            │  │                              [ Send ]     │
            │  └──────────────────────────────────────────┘
```

### 6.4 Icon

Source: `tools/assets/hopa-backend-workder.jpeg` (the filename's `workder` is as
given — do not rename it). Measured 2026-11-26:

| Property | Result |
|---|---|
| Size / format | 1024 × 1024, JPEG, 24bpp RGB, **no alpha** (381 KB) |
| Corners | `#fdfbfc` … `#fefefe` — effectively `--paper` |
| Warm pixels (hue 20–70°, sat > 0.25) | **0** |
| Dominant tones | `#407060` → `--accent` (Δ15); `#306050` → `--accent-ink` (Δ10); panels `#b0c0c0` → `--muted` (Δ15) |

Palette compliance passes. The fur's hue is ≈161° against `--accent`'s ≈171° — ten
degrees greener at the same lightness, indistinguishable at 40px.

**Crop:** `x=130, y=110, side=770` (12.7% inset), the full figure with the raised
paw. Tested at 40px: the full frame leaves the figure at ~24px; a bust crop
(`170,120,700`) fills the circle but cuts the tool belt, the mascot's
second-strongest read after the hat.

**Derived, committed 2026-11-26** in
`services/backend/src/main/resources/console/`: `mark-80.png` (80 × 80, 13.8 KB)
and `favicon-32.png` (32 × 32, 2.7 KB), both 32bpp ARGB with circular alpha
(corner alpha 0), crop baked in. The original is never served.

**Ring:** the ground is ≈`--paper` against a `--surface` rail, so the avatar gets
a 1px `--line` ring; the PNGs already carry circular alpha, so it is one rule:

```css
.mark { display: block; border-radius: 50%; box-shadow: 0 0 0 1px var(--line); }
```

Rules: one place, one size (40px rail, 28px narrow, tab icon); never recoloured,
redrawn or stretched; its tones are not sampled into the UI; no page
illustrations, backgrounds or empty-state art.

### 6.5 Implementation tasks

- [ ] `get("/console/assets/{name}")`: allowlisted bytes for `app.css`,
      `mark-80.png`, `favicon-32.png` — no dependency, no path join.
- [ ] `ConsoleViews.page()` becomes `layout(active, title) { … }`; one function
      owns the rail, top bar and footer.
- [ ] Restyle every view; every value stays a text node or an attribute, never
      `unsafe`.
- [ ] `PRODUCT.md`, `DESIGN.md`, `.impeccable/config.json` (done).
- [ ] Mechanical detector run and screenshot checks at 1440 and 390 wide.

```kotlin
private data class Asset(val body: ByteArray, val type: ContentType)

get("/console/assets/{name}") {
    val asset = when (call.parameters["name"]) {
        "app.css"        -> Asset(css, ContentType.Text.CSS)
        "mark-80.png"    -> Asset(mark80, ContentType.Image.PNG)
        "favicon-32.png" -> Asset(favicon, ContentType.Image.PNG)
        else -> null
    } ?: run {
        call.respond(HttpStatusCode.NotFound)
        return@get
    }
    call.respondBytes(asset.body, asset.type)
}
```

---

## 7. Slices

Each slice ships alone and proves itself.

**Slice 1 — pair from the app**
- [ ] engine protocol (`backend.qr`, `backend.pair.result`, `backend.status`,
      `backend.pair.cancel`, id echo, QR seam)
- [ ] core reply seam (`task.result`/`error`/`progress`/`ack → onReply`)
- [ ] engine writes `RuntimeConfig.setGatewayState` from its own state
- [ ] window panel + events; retire the window's gateway; approval over the
      engine; delete `hopa.gateway`
- [ ] tests: `ProtocolFamilyTest` (pair carries an id), `BackendHandlerTest` (qr,
      result, gate, cancel), `BackendDeliveriesTest` (reply frames reach
      `onReply`), `Hopa.Windows.Tests` (transport events, panel states, approval
      delegation)
- **Proves:** phone scans → app says Connected → restart → still Connected →
  `devices.last_seen_at` moves within a second; `capabilities` reports
  `gatewayPaired=true, phoneOnline=true`.

**Slice 2 — action backend**
- [ ] `admin.action` in `TaskRouter`, `POST /console/action`, accepted and status
      pages
- [ ] tests: `TaskRouterTest` (type allowed, goal limits, tenancy, audit name),
      `ConsoleTest` (form on the household page, 403 cross-household, 422 empty
      goal, status page shows state and no text)
- **Proves:** a `tasks` row exists, state `queued`, and the console shows it.

**Slice 3 — action clients**
- [ ] engine plaintext branch; core `admin.action` dispatch; phone status-only reply
- [ ] tests: `BackendGatewayTest` (goal → result, error path), `BackendClient`
      routing, `RemoteTaskRunnerTest` (an `admin.action` run answers with no
      summary and the answer stays local)
- **Proves:** console → phone reaches `done`; console → PC runs a turn whose
  answer appears at the PC, not in the console page; `task_events` for an
  `admin.action` holds no device output.

**Slice 4 — dashboard**
- [ ] stylesheet, layout, every page
- [ ] serve the committed icon derivatives, rail mark and `<link rel="icon">`
- [ ] tests: `ConsoleTest` for each page's essentials; detector clean; screenshots
- **Proves:** screenshots at 1440 and 390 with no bare tables left, the mark at
  40px beside the wordmark, and the tab icon served at 32px instead of 381 KB.

---

## 8. Verification (live)

```powershell
# backend (the Android wrapper is the repo's gradlew; there is none at the root)
apps\android\gradlew.bat -p services\backend installDist
java -cp "services\backend\build\install\hopa-backend\lib\*" com.hopa.backend.MainKt
# tests:
#   apps\android\gradlew.bat -p services\backend :test --tests "*ConsoleTest*"
# console: http://127.0.0.1:8080/console  (admin@hopa.test / HOPA_ADMIN_PASSWORD)

# phone
adb reverse tcp:8080 tcp:8080

# sidecar + window (net10.0-windows needs DOTNET_ROOT)
apps\android\gradlew.bat -p apps\windows\sidecar installDist
$env:DOTNET_ROOT = "$env:USERPROFILE\.dotnet10"
dotnet build apps\windows\Hopa.Windows
```

| Check | Evidence to record |
|---|---|
| Pair from the window | screenshot of Connected + `select id, platform, last_seen_at from devices order by created_at desc limit 1` |
| Restart keeps it | reopen the app; `backend.status` reports paired and online |
| Console action → phone | `tasks` row reaching `done`; phone shows the run |
| Console action → PC | engine record + the answer in the PC app, absent from the console page |
| No device output stored | `select body from task_events where task_id = '<action>'` holds no answer text |
| Payment gate intact | a payment-class action still stops for a person; the console cannot approve it |
| Phone approval intact | a PC action with the phone paired gets the phone's answer through the engine; with no phone it falls back to the person |
| Tenancy | a device id from another household answers `403 E_TENANT` and no row is written |
| PII | reveal writes `admin.pii.reveal`; the list stays masked |

Record runs in `docs/project/evidence/B2-accounts.md` (or a sibling), with ids and
timestamps, in the style already used there.

---

## 9. Risks and calls

| Risk | Call |
|---|---|
| Approval-over-engine is the fiddliest piece. | Policy stays in `ApprovalRouter`; only transport moves. The core reply seam (§4.2) is part of slice 1 — without it every ask times out into a deny. If it stalls, ship the pairing panel with the phone branch disabled and **logged**, never silently denying. |
| Two sockets during migration. | One commit stops the window's socket and enables the engine's. |
| `backend.pair` contract change breaks the protocol test by design. | Update `ProtocolFamilyTest`'s `withoutId` set in the same commit. |
| A console action is plaintext on the way out. | Admin-only, audit-logged, capped, and it carries *server* words only; device output for this flow is status-only (§5.3). |
| Device-to-device reply bodies are plaintext and persisted. | Pre-existing; neither fixed nor widened (§10). |
| The icon is a photograph: no alpha, near-paper ground. | Masked, not knocked out; measured crop so it reads at 40px; 1px `--line` ring covers the ground/wall difference. |
| A five-minute admission inside a request handler would block the stdin loop. | Accept-then-event; cancel from a coroutine. |
| The status page is a poll. | `meta refresh` only while non-terminal; no JS, no websocket. |

---

## 10. Out of scope (recorded, not built)

- Unpair/forget a device (re-pair overwrites the session).
- Live phone presence in the PC window beyond "Connected".
- Household-facing console (auth, tenancy, pages).
- Sealed console actions (would need the household key in the console).
- Sealing device-to-device reply bodies (`summary`, `note` are plaintext today).
- A console activity feed (the event log is stdout-only).
