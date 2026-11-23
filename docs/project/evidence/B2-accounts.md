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

## Pre-demo

- [ ] Confirm the Render edge overwrites a client-supplied `X-Forwarded-For`
      before the demo (the unauthenticated per-IP rate limits key on the first
      XFF hop), or record the trusted proxy hop and change the limiter key in
      `remoteAddress` to match.

