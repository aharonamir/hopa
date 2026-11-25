# B2 — week-2 accounts/admission/console evidence

Branch: `backend-week2-B`. Date: 2026-11-23 (skeleton — **starts unticked**).
Spec: [`../plan/backend/week2.md`](../plan/backend/week2.md) §8. Slice plan:
[`../plan/backend/tasks/week2b.md`](../plan/backend/tasks/week2b.md).

This file records what is proven, with which command, and what still needs a
credential, a device or a timing run. A box is **not** ticked on intention: each
row is ticked by the controller/user only after the run named in its "Where
proven" column has produced the evidence written next to it. Rows that can be
proven by the repository's automated tests name the exact test class but stay
unticked until the run is recorded.

| # | Check | Target | Where it is proven |
|---|---|---|---|
| 1 | A turn typed on the phone appears on the PC, and back | ≤ 2 s (target, measure and record) | evidence file `B2-accounts.md` with timestamps |
| 2 | A revoked device is cut off | in-flight socket closed ≤ 5 s; token refresh refused; wrap row gone | integration test + manual timing in the evidence file |
| 3 | A PC admitted by a camera-scanned QR runs one task each way | 20/20 with encrypted bodies; the PC unwraps its key | evidence file; falls back to operator QR with the gap carried, never skipped |
| 4 | Sign-up → sign-in round trip on the phone | new household of one; second sign-in gets a signed-in device | integration test |
| 5 | SMS clash policy (flag on, fake `SmsPort`) | a verified login binds and stamps the unverified account | unit test |
| 6 | Console lookup by phone, member-id, household-id | correct row; every read appears in the access log | integration test + log line |
| 7 | `diag.ping` from the console to an offline phone | queued, FCM wake, arrives; audit row names the admin | integration test with FakeFcm |
| 8 | Cross-tenant suite still green | 1,000 attempts, 0 rows (extended with accounts + wraps + pending pairings) | CI job |
| 9 | No plaintext payload or phone number in logs | grep of the week's logs is clean | evidence file, command shown |

---

## Check 1 — a turn typed on the phone appears on the PC, and back

| Field | Value |
|---|---|
| Check | A turn typed on the phone appears on the PC, and back |
| Target | ≤ 2 s (target, measure and record) |
| Command / Where proven | Sync v0 round-trip; no automated harness yet — the sync v0 mini-design lands as `tasks/week2-sync.md`. Warm the service first (`/v1/health`), pair a phone and a PC into one household, type a turn on the phone, watch it land on the PC, then type a turn on the PC and watch it land on the phone. Record the trace id and the wall-clock delta for each direction. |
| Evidence | `pending` — trace ids `____`, timestamps `____` |
| Status | - [ ] |

## Check 2 — a revoked device is cut off

| Field | Value |
|---|---|
| Check | A revoked device is cut off |
| Target | in-flight socket closed ≤ 5 s; token refresh refused; wrap row gone |
| Command / Where proven | Automated: `apps\android\gradlew.bat -p services/backend :test --tests "*DeviceRevokeTest*"` (revoke cuts the token family and the wrap row and kicks the live socket) and `--tests "*KickTeardownTest*"` (the socket teardown path). Manual timing of the ≤ 5 s in-flight close is recorded below. |
| Evidence | `DeviceRevokeTest` + `KickTeardownTest` (run `pending`); manual timing `pending` — timestamps `____` |
| Status | - [ ] |

## Check 3 — a PC admitted by a camera-scanned QR runs one task each way

| Field | Value |
|---|---|
| Check | A PC admitted by a camera-scanned QR runs one task each way |
| Target | 20/20 with encrypted bodies; the PC unwraps its key |
| Command / Where proven | Automated admission/unwrap: `apps\android\gradlew.bat -p services/backend :test --tests "*AdmissionTest*"` (vouch, claim-with-wrap, the PC unwraps its key). The deployed camera-scan round-trip (one real phone + one real PC) is manual; if the camera path is unavailable the run falls back to the operator QR and the gap is carried, never skipped. |
| Evidence | `AdmissionTest` (run `pending`); deployed 20/20 round-trip `pending` — trace ids `____` |
| Status | - [ ] |

## Check 4 — sign-up → sign-in round trip on the phone

| Field | Value |
|---|---|
| Check | Sign-up → sign-in round trip on the phone |
| Target | new household of one; second sign-in gets a signed-in device |
| Command / Where proven | `apps\android\gradlew.bat -p services/backend :test --tests "*AccountRoutesTest*"` |
| Evidence | `AccountRoutesTest` (run `pending`) |
| Status | - [ ] |

## Check 5 — SMS clash policy (flag on, fake `SmsPort`)

| Field | Value |
|---|---|
| Check | SMS clash policy (flag on, fake `SmsPort`) |
| Target | a verified login binds and stamps the unverified account |
| Command / Where proven | `apps\android\gradlew.bat -p services/backend :test --tests "*OtpTest*"` |
| Evidence | `OtpTest` (run `pending`) |
| Status | - [ ] |

## Check 6 — console lookup by phone, member-id, household-id

| Field | Value |
|---|---|
| Check | Console lookup by phone, member-id, household-id |
| Target | correct row; every read appears in the access log |
| Command / Where proven | `apps\android\gradlew.bat -p services/backend :test --tests "*LookupTest*"` (admin API resolves by phone / member id / household id, and every PII read writes an `admin.pii.read` log line) and `--tests "*ConsoleTest*"` (the rendered lookup page). |
| Evidence | `LookupTest` + `ConsoleTest` (run `pending`); captured `admin.pii.read` log line `pending` |
| Status | - [ ] |

## Check 7 — `diag.ping` from the console to an offline phone

| Field | Value |
|---|---|
| Check | `diag.ping` from the console to an offline phone |
| Target | queued, FCM wake, arrives; audit row names the admin |
| Command / Where proven | `apps\android\gradlew.bat -p services/backend :test --tests "*DiagSendTest*"` (against `FakeFcm`) |
| Evidence | `DiagSendTest` (run `pending`) |
| Status | - [ ] |

## Check 8 — cross-tenant suite still green

| Field | Value |
|---|---|
| Check | Cross-tenant suite still green |
| Target | 1,000 attempts, 0 rows (extended with accounts + wraps + pending pairings) |
| Command / Where proven | CI job; locally: `$env:HOPA_ISOLATION_ATTEMPTS = "1000"` then `apps\android\gradlew.bat -p services/backend :test --tests "*TenantIsolationTest*"` |
| Evidence | `TenantIsolationTest` (run `pending`) |
| Status | - [ ] |

## Check 9 — no plaintext payload or phone number in logs

| Field | Value |
|---|---|
| Check | No plaintext payload or phone number in logs |
| Target | grep of the week's logs is clean |
| Command / Where proven | Scan the captured backend logs for phone numbers and raw tokens; bodies/payloads are not logged at all, so a clean scan shows no `+<digits>` and no `Bearer ` token. |
| Evidence | `pending` — command output `____` |

```powershell
# Replace <logfile> with the captured backend log. A clean run prints nothing.
Select-String -Path <logfile> -Pattern 'Bearer ', '\+[0-9]{8,15}'
```

---

## Commands

```powershell
# Whole backend suite (Docker Desktop must be running; Testcontainers)
apps\android\gradlew.bat -p services/backend :test --rerun-tasks

# One row at a time (rows 2–8)
apps\android\gradlew.bat -p services/backend :test --tests "*DeviceRevokeTest*"
apps\android\gradlew.bat -p services/backend :test --tests "*KickTeardownTest*"
apps\android\gradlew.bat -p services/backend :test --tests "*AdmissionTest*"
apps\android\gradlew.bat -p services/backend :test --tests "*AccountRoutesTest*"
apps\android\gradlew.bat -p services/backend :test --tests "*OtpTest*"
apps\android\gradlew.bat -p services/backend :test --tests "*LookupTest*"
apps\android\gradlew.bat -p services/backend :test --tests "*ConsoleTest*"
apps\android\gradlew.bat -p services/backend :test --tests "*DiagSendTest*"
$env:HOPA_ISOLATION_ATTEMPTS = "1000"
apps\android\gradlew.bat -p services/backend :test --tests "*TenantIsolationTest*"

# Repo checks
py -3 tools/checks/check-links.py
python tools/scripts/plan-status.py --plan docs/project/plan/backend
```

## Notes

- Rows 1, 3 and 9, and the manual timing half of row 2, need a deployed or
  physical-device run; their placeholders are marked `pending` above and are
  ticked only when the trace id / timestamp is written in.
- Rows 2, 4, 5, 6, 7 and 8 are provable by the repository's automated suite; the
  test class is named but the box stays unticked until the run is recorded.
- This file is indexed from [`docs/README.md`](../../README.md); the week-2 plan
  checklist entry is [`../plan/backend/week2.md`](../plan/backend/week2.md) W2.8.

## Local end-to-end run — 2026-11-25

Staging-style run on one Windows host: Postgres 16 in Docker on `127.0.0.1:55432`,
the backend from `services/backend` `installDist`, a real Android phone
(`2506BPN68G`, Android 17) over `adb reverse tcp:8080 tcp:8080`, and the Windows
sidecar admitted by the phone. `HOPA_PUBLIC_SCHEME=ws` (LAN override, default
stays `wss`), `HOPA_ADMIN_LOOKUPS=true`, admin seeded from env. No Render service
was used and no secret is recorded here.

| # | Check | Result | Evidence |
|---|---|---|---|
| 3 | PC admitted by a phone QR; PC unwraps its key | **Admitted; unwrap and live gateway proven. Paste fallback used, camera auto-detect did not fire.** | Pending session `da478223-523c-469b-93e2-1b20f922492e`: vouched `10:25:23Z`, claimed `10:25:27Z`; PC device `301803a2-add0-4ba0-b778-d8fe4a8d370b`, household `f108f320-dc6e-425f-b043-cc50cc048549`; sidecar printed `admitted as 301803a2… to household f108f320…`, wrote `backend-session.json` at `12:25:27` local and emitted `{"type":"ready","version":1}`. Console listed the PC with `online: true`. The QR path itself rendered and the phone camera preview showed it; auto-detect did not fire and the paste path completed the vouch (gap carried). One encrypted task each way is **not** proven (sync v0 is out of this slice). |
| 4 | Sign-up → sign-in round trip on the phone | **Proven on the real phone.** | Sign-up created household `f108f320…` and device `fcea6ab0-f34c-4fc2-ab7a-547efd5aee23`; sign-in on the same account created a second signed-in device `946fd66a-2c4f-49f0-b666-96b1098c8d24`. `GET /v1/admin/devices?household=…` returned both. |
| 6 | Console lookup by phone / member-id / household-id | **Lookup by phone proven; PII line written.** | `GET /v1/admin/households?query=+972…4567` returned `householdId f108f320…`, `memberId c8cb6ca5…`, status `unverified`; the server wrote `{"event":"admin.pii.read","query_kind":"phone","query_hash":"6d7cdf3b1cac7978"}`. Member-id and household-id lookups were exercised by the automated `LookupTest` only. |
| 7 | `diag.ping` from the console to a device | **Delivered to the live PC.** | `POST /v1/admin/devices/301803a2…/diag {type:"diag.ping"}` answered `202` with task `99ddaeb0-5638-4976-8a70-0beb9bf5dcbe`, trace `00-b2ef7aed98402bf19a62af48037f9a5b-abad6a15749d2e44-01`, state `delivered`; the task row became `diag.ping | done | from_device null`. Offline FCM wake and the phone-side ack are **not** proven here. |
| 9 | No plaintext payload or phone number in the logs | **Local backend log clean.** | `Select-String -Path <backend log> -Pattern 'Bearer ', '\+[0-9]{8,15}'` → **0 matches**. The pair secret lives only in the sidecar's stderr/QR and was not written to the backend log. |

Not proven by this run: #1 (sync v0 does not exist yet), #2 manual 5 s
revocation timing, #3's encrypted task round-trip and camera auto-detect, #5
(unit test only), #7's offline FCM wake and phone-side delivery.

### Findings from the local run

1. **Duplicate `PendingState` class.** `core/backend` and `services/backend` both
   define `com.hopa.backend.pairing.PendingState`. The `installDist` wildcard
   classpath loaded the client class first and `/v1/pair/pending/resolve` failed
   with `NoSuchMethodError: PendingState.<init>(7 strings)` → HTTP 500. The run
   was completed by launching with `lib/hopa-backend.jar` ahead of `lib/*`.
   Proper fix: rename one class and add a runtime-boot smoke test.
   **Fixed** in `3a8faa9d`: the server copy is `PendingPairingState`. Verified by
   starting the backend with a plain `lib\*` classpath — `POST
   /v1/pair/pending/resolve` answered `200 {"state":"pending"}` (see the second
   run below).
2. **Android stores the HTTP base as the gateway URL.** Sign-up and sign-in save
   `backendUrl = http://host:port`, so `BackendSession` never opens
   `ws(s)://host/v1/gateway`; the phone shows "failed: the gateway is
   unreachable" and its 15-minute access token is never refreshed. A vouch after
   the token TTL therefore returns `E_AUTH`. Fix in
   `app/account/AccountViewModel.persist` (or derive it in
   `BackendIdentity.stored`).
   **Fixed** in `c3c5f068` (`BackendUrls.gatewayUrl()` + `BackendIdentity.stored()`
   + `AccountViewModel.persist`) and `ee2fdea4` (a refresh keeps the socket URL).
   Regression tests: `PairingLinksTest` (`gatewayUrl`), `SignInViewModelTest`
   (stored `backendUrl` is the socket), `BackendSessionTest` ("a refresh keeps the
   v1 gateway socket, not the HTTP origin"). Proven live in the second run below.

## Fixes verified on a second run — 2026-11-25 (afternoon)

Branch `backend-tests` (nothing merged to `main`). Commits: `7569b86e` LAN `ws`
scheme, `c3c5f068` gateway socket URL, `3a8faa9d` `PendingPairingState` rename,
`ee2fdea4` refresh keeps the socket URL.

| # | Check | Result | Evidence |
|---|---|---|---|
| — | Backend boots with the plain `lib\*` classpath | **Fixed** | `java -cp "…\hopa-backend\lib\*" com.hopa.backend.MainKt`; `GET /v1/health` → `{"ok":true,"version":"0.1.0","db":"ok"}`; `POST /v1/pair/pending/resolve` → `200 {"state":"pending"}` (no jar-order workaround). |
| 4 | Phone signed in, household and member reused | **Proven** | Phone `2506BPN68G` (Android 17, serial `U8RSJVSWDYIRRK5D`), app `0.1.0-261125-1300` installed `14:24:26`; account `47f3d1b6-a5d3-4194-949a-439c1a6dac38` `+972501234567`, member `c8cb6ca5-66f2-4a30-b272-6e3286f28eca`, household `f108f320-dc6e-425f-b043-cc50cc048549`. |
| 7 | `diag.ping` reaches a **phone** | **Delivered and acked** | Task `f849f5af-5da4-4ad4-986e-64ef301fa8f6`, trace `00-fd7bdab7f2bb007fba8f34acbb92bad2-76f4acf87bcc5a75-01`, `delivered` → `acked` in 102 ms; phone row `946fd66a-2c4f-49f0-b666-96b1098c8d24` `online: true`. |
| 3 | Fresh PC admission by a phone-vouched QR | **Proven end to end** | Pending `2cd628a1-a7ef-4033-a163-1faa313cf693` ("Windows PC", fingerprint `8bb258f5`, matches the sidecar's own printed fingerprint): vouched by phone `946fd66a…` at `12:32:09.479608Z`, claimed `12:32:13.956524Z`, `wrapped_key` 60 bytes, new PC device `3e0ee6ed-c6f8-4276-9733-53dbaf6e25d9` `online: true` at `12:32:14.339Z`; sidecar printed `admitted as 3e0ee6ed-… to household f108f320-…` and `{"type":"ready","version":1}`. |
| 7 | `diag.ping` reaches the newly paired **PC** | **Delivered and done** | Task `68212479-9b69-471d-b268-412ef0d03de5`, trace `00-c6e0b18c84ef0694841d319f7ae970f2-0528a95b3e8bf042-01`, `delivered` → row `done` at `12:32:44.926Z`. |
| 9 | No plaintext payload or phone number in the log | **Clean** | `Select-String -Path <backend log> -Pattern 'Bearer ', '\+[0-9]{8,15}'` → 0 matches. |

Gateway-socket fix, proven live: the stored `backend_url` was still
`http://127.0.0.1:8080` from the earlier build, yet the card read
`Device 946fd66a · connected` and the device row went `online: true` after a
relaunch that was more than 90 minutes past the access-token TTL — so the read
path normalised the URL and the refresh kept it.

Gap carried: the camera preview showed the QR, but ML Kit auto-detect did not
fire; the scan screen's paste field completed the vouch (`Scan PC QR` →
`Use this code` → `Vouch for this PC`). Sync v0 (`#1`) still does not exist, so
one encrypted task each way remains unproven.

### Clean-install run: register → connect → pair, in one household

`adb shell pm clear com.hopa.android` gave the same state as a fresh install, so
the whole chain ran on the final build (`0.1.0-261125-1300`).

| Step | Result | Evidence |
|---|---|---|
| Register from the phone with a phone number | **New account, member and household** | Sign-up (+972501234998) created account `b29ae304-5ef1-43e2-bd4f-9255a6e00eb7`, member `62809548-4ff4-45d3-add7-25361576a91c`, household `8e4b5b01-4a47-4ec2-95a2-a752d18cec5b` at `12:39:15.549Z`; the member row joined the account and the household. |
| Connect to the backend | **Socket stored and opened on the first cold start** | Stored `backend_url` = `ws://127.0.0.1:8080/v1/gateway` (read out of `hopa_settings.preferences_pb`); device `ed713b14-b564-4671-b6f1-1db2c965d678` `last_seen_at` `12:39:36.647Z`. |
| Pair the PC by QR into that household | **Vouched and claimed** | Pending `a17f2963-cd0f-42e1-8af3-131d824968be` ("Windows PC", fingerprint `a7dbcf8c`): vouched by `ed713b14…` at `12:42:19.107824Z`, claimed `12:42:23.69831Z`, `wrapped_key` 60 bytes; PC `f8bbeb8e-f4e8-4688-8efd-f9986b3c9c7c` `last_seen_at` `12:42:23.848Z`; sidecar printed `admitted as f8bbeb8e-… to household 8e4b5b01-…`. |
| `diag.ping` from the console **form** to the phone | **Delivered and acked** | `POST /console/device/ed713b14…/diag` → "Diagnostic accepted", task `4e0798a8-66ed-46fd-a991-15a1d1bd2d53`, trace `00-28dbfeda65c325b1e2c8cf5b48f3f082-680b1b18f4159a8b-01`, `delivered` → `acked` at `12:43:24.737Z`. |
| `diag.ping` to the PC in the same household | **Delivered and done** | Task `a990504b-a570-48db-ad68-113d6174f950`, trace `00-0a34797eee3f30844fd56cbb4be6cc0d-136bd26283b7e4a4-01`, row `done`. |

Console (`/console`, signed in as `admin@hopa.test`) showed the household with
both devices `online` and the household/queue tables; the lookup wrote
`admin.pii.read` for the phone queries.

Two product notes from this run, neither blocking:

- The gateway session starts on the next app launch, not when sign-up finishes:
  right after registering the device row existed but `last_seen_at` stayed
  empty until the app was force-stopped and started.
- The "My computer" header pill reads `Not set up` after a successful gateway
  pairing, because `paired` is still computed from the legacy bridge token
  (`state.settings.bridgeUrl && state.bridgeKey`), not from the gateway device.
  The gateway card below it read `Device … · connected`.

### The WPF app joined the same household

The Windows app (`apps/windows/Hopa.Windows`, build `net10.0-windows` from
`11:18`) was paired as a third device, so the PC side of the link is the real
app and not only the headless sidecar.

| Step | Result | Evidence |
|---|---|---|
| Start the app | **Runs** | `Hopa.Windows.exe` needs `DOTNET_ROOT=%USERPROFILE%\.dotnet10` (runtime 10.0.5); the machine default `C:\Program Files\dotnet` only has 8.0.34 and the exe exits with “You must install or update .NET”. |
| Mint an operator link for the phone's household | **Fixed, then minted** | `POST /v1/pair/sessions` (bootstrap token) with `householdId` of `8e4b5b01…` returned `hopa://pair?v=1&h=8e4b5b01…&p=Ei23Z…&u=ws://127.0.0.1:8080/v1/gateway`. Before the fix it answered `E_SCHEMA unknown household` (see below). |
| Pair from Settings → General → “Phone link (backend gateway)” | **Paired** | The field read `Paired as be27c226-28a1-4703-bda1-c127e6dc6da2.`; device `be27c226…` named `TRC240300069-X1` (the machine name), household `8e4b5b01…`. |
| Socket after relaunch | **Online; queued ping delivered** | `last_seen_at 13:16:23.451Z`; the `diag.ping` that was `queued` while the app was closed became `delivered` at `13:16:23.498Z` on connect. |

Two more findings:

3. **`mint` into an existing household was refused under a BYPASSRLS role.**
   `PairingService.mint` checked `select count(*) from households` inside the
   tenant scope and compared it to 1. With the local `postgres` superuser, RLS is
   bypassed, the count saw every household, and a valid `householdId` was
   rejected as `E_SCHEMA unknown household`. The check now filters
   `where id = ?::uuid`, so it is exact with or without RLS. `PairingHttpTest`
   (9 tests, including “a session minted for an existing household joins it”) is
   green.
4. **The Windows gateway ignores `diag.*`.** `GatewayFrameKind` has no
   diagnostic kind, so a console `diag.ping` reaches the app and stays
   `delivered`; the Android client routes `diag.*` to its handler and acks. The
   PC app therefore cannot answer a console diagnostic yet.
   The app's main window also still shows `No bridge` after a gateway pairing,
   for the same reason as the phone pill: that label is the legacy bridge.

## Pre-demo

- [ ] Confirm the Render edge overwrites a client-supplied `X-Forwarded-For`
      before the demo (the unauthenticated per-IP rate limits key on the first
      XFF hop), or record the trusted proxy hop and change the limiter key in
      `remoteAddress` to match.

