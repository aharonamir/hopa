# Backend week 2, slice B: accounts, console, Android

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans (or
> superpowers:subagent-driven-development) to implement this plan task-by-task.
> Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Real users: phone-number + password accounts with a household of one, the
optional SMS OTP path, the admin console (sign-in, health, household & device
lookup, diag sender), and the Android client — sign-in screen, two-mode operation,
camera QR scan that vouches the PC.

**Architecture:** Credentials live in a new non-tenant `accounts` table
(`members.account_id` links them into a household); argon2id hashes, E.164 phones,
opaque tokens exactly as pairing already issues. The console is server-rendered
kotlinx.html pages inside the same Ktor app behind `/console`, consuming the admin
API and never rendering conversation contents. Android keeps exactly two modes —
`backendUrl` configured or local-only — and reuses `core/backend`'s client plus the
new `com.hopa.backend.crypto.KeyWrap` from slice A Task 2 to wrap the household key
when vouching.

**Tech Stack:** Kotlin 2.4.21, Ktor 3.6.1 (`ktor-server-html-builder` new),
BouncyCastle argon2id (slice A Task 2), Testcontainers, CameraX + ML Kit
barcode-scanning on Android.

**Spec:** [`../week2.md`](../week2.md) §3–§6, built from
[`docs/superpowers/specs/2026-11-22-pairing-accounts-design.md`](../../../../superpowers/specs/2026-11-22-pairing-accounts-design.md).

## Global Constraints

- Do not bump the shared catalog's pins; new libraries only: `ktor-server-html-builder`,
  `barcode-scanning` (ML Kit), `camera-*` (CameraX).
- The phone number is E.164-normalized server-side, unique, and is **only** a login
  lookup — never a key, never on the wire, never in a token.
- Wrong phone and wrong password are the same uniform `E_AUTH`; no user enumeration.
- Rate limits per IP **and** per phone on every unauthenticated account route.
- `accounts`, `admins`, `admin_sessions` are not tenant tables; `household_key_wraps`
  and `pending_pairings` are (RLS on, `FORCE` like the rest).
- Admin lookups into tenant rows go only through the migration's `SECURITY DEFINER`
  functions owned by `hopa_admin`; the app role has no `BYPASSRLS` and no blanket
  tenant GUC.
- Every admin read of a phone number writes an access-log line first.
- `HOPA_SMS_ENABLED` defaults to **false**; when false, the OTP routes return
  `E_UNSUPPORTED` and no code is generated.
- Spec changes land with their handlers in the same PR.
- Android release builds ship a null `backendUrl` `buildConfigField`; the value comes
  from `local.properties` in debug only.
- Gradle is always `apps\android\gradlew.bat`, run from the repository root.

## Review Focus

- Sign-up with `"+972 50-123-4567"` and with `"972501234567"` creates **two** accounts
  (must normalize to one) / or a duplicate-phone sign-up returns something other
  than a uniform refusal.
- A `deleted_at`-tombstoned account's phone cannot be re-registered, and its sign-in
  is refused.
- An OTP verify with a stale/used code still logs in (codes must be single-use and
  expiring).
- The console lookup page renders a phone number for an admin **without** an
  access-log line (PII read must be logged).
- Android with a configured-but-unreachable backend shows the pairing UI anyway
  (must degrade to local-only, not brick).

Each line has a test in Task 3, Task 4, Task 5 or Task 7.

---

### Task 1: V2 migration — accounts, wraps, admins, pending pairings

**Files:**
- Create: `services/backend/src/main/resources/db/migration/V2__accounts.sql`
- Test: `services/backend/src/test/kotlin/com/hopa/backend/store/V2SchemaTest.kt` (Testcontainers)

**Interfaces:**
- Consumes: `V1__init.sql`, `PostgresHarness` (`hopa_owner` migrates, `hopa_app` serves).
- Produces (Tasks 3–6 here and Tasks 4–6 of slice A build on it): tables
  `accounts`, `household_key_wraps`, `admins`, `admin_sessions`, `pending_pairings`;
  `alter table members add column account_id uuid null references accounts`;
  `alter table tasks alter column from_device drop not null` (server-authored diag
  tasks); RLS enable+force + household policy on `household_key_wraps` and
  `pending_pairings`; **no** RLS on `accounts`/`admins`/`admin_sessions`; four
  functions owned by the non-login `hopa_admin` (`BYPASSRLS`) role, living in the
  optional `db/admin/V5__admin_lookups.sql` migration (applied only when
  `HOPA_ADMIN_LOOKUPS=true`): `admin_resolve_member(uuid)`,
  `admin_resolve_household(uuid)` and `admin_resolve_phone(text)` each return
  `(household_id, member_id, display_name, account_id)`, and
  `admin_list_devices(uuid)` returns
  `(device_id, platform, name, last_seen_at, revoked_at)`. All are
  `SECURITY DEFINER`, `search_path = public, pg_temp`, and return minimum columns.

- [ ] **Step 1: Write the failing tests** — migration applies fresh and re-applies as
  a no-op; as `hopa_app` with no tenant set: `select count(*) from
  household_key_wraps` returns 0 (RLS force) while `select count(*) from accounts`
  works (non-tenant); `admin_resolve_member('00000000-...-0000')` returns no row;
  each function's result type is exactly the columns above (`information_schema`).
- [ ] **Step 2: Run to watch them fail** — Run:
  `apps\android\gradlew.bat -p services/backend test --tests "*V2SchemaTest*"`
  Expected: Flyway error — `V2__accounts.sql` not found (or table missing).
- [ ] **Step 3: Write the migration** — follow V1's style (plain SQL, one
  transaction, household policy named like the existing ones); `revoked` semantics
  untouched; index `accounts(phone) where deleted_at is null` (partial unique).
- [ ] **Step 4: Run to watch them pass.**
- [ ] **Step 5: Commit**

```powershell
git add services/backend/src/main/resources/db/migration
git commit -m "feat(backend): V2 accounts, wraps, admins, pending pairings"
```

---

### Task 2: E.164 + argon2 utilities and the admin seed

**Files:**
- Create: `services/backend/src/main/kotlin/com/hopa/backend/accounts/Phones.kt`
- Create: `services/backend/src/main/kotlin/com/hopa/backend/accounts/Passwords.kt`
- Create: `services/backend/src/main/kotlin/com/hopa/backend/accounts/AdminSeed.kt`
- Test: `services/backend/src/test/kotlin/com/hopa/backend/accounts/AccountsUtilTest.kt`

**Interfaces:**
- Consumes: `Argon2` from slice A Task 2.
- Produces: `object Phones { fun normalize(raw: String): String? }` — accepts
  `+`, spaces, dashes, leading double-zero; requires 8–15 digits after
  normalization; returns `+<digits>` or null; `object Passwords { fun hash(p:
  CharArray): String; fun verify(p: CharArray, encoded: String): Boolean }`;
  `class AdminSeed(db) { fun upsertFromEnv(email: String?, password: String?) }`
  called from `Main.kt` after `migrate()` (no-op when either env var is absent).

- [ ] **Step 1: Write the failing tests** — `"+972 50-123-4567"`,
  `"972501234567"`, `"00972501234567"` all normalize to `+972501234567`; `"abc"`,
  `"12345"`, null → null; hash/verify round-trip; wrong password fails.
- [ ] **Step 2: Run to watch them fail.**
- [ ] **Step 3: Implement** and wire `AdminSeed.upsertFromEnv(System.getenv("HOPA_ADMIN_EMAIL"), System.getenv("HOPA_ADMIN_PASSWORD"))` into `Main.kt`.
- [ ] **Step 4: Run to watch them pass.**
- [ ] **Step 5: Commit**

```powershell
git add services/backend/src
git commit -m "feat(backend): E164 phones, argon2 passwords, admin seed"
```

---

### Task 3: Sign-up and sign-in endpoints

**Files:**
- Create: `services/backend/src/main/kotlin/com/hopa/backend/accounts/AccountRoutes.kt`
- Create: `services/backend/src/main/kotlin/com/hopa/backend/accounts/AccountService.kt`
- Modify: `spec/openapi/backend.v1.yaml` (`/v1/accounts`, `/v1/session`)
- Test: `services/backend/src/test/kotlin/com/hopa/backend/accounts/AccountRoutesTest.kt` (Testcontainers)

**Interfaces:**
- Consumes: Task 1's tables, Task 2's utilities, `TokenIssuer`, `RateLimiter`,
  `receiveBounded`, `respondError`.
- Produces: `POST /v1/accounts {phone, password, platform, name, publicKey, alg,
  selfWrappedKey, pushToken?}` → 201 `{accountId, deviceId, householdId,
  accessToken, refreshToken, expiresAt}` (one `db` transaction: account +
  household-of-one + owner member + device + `household_key_wraps` row for the
  phone itself); `POST /v1/session {phone, password, platform, name, publicKey,
  pushToken?}` → 201 with tokens for the new signed-in device (no wrap, no
  history). `class AccountService(db, tokens, clock, perMinute: Int = 5)`.

- [ ] **Step 1: Write the failing tests** — happy sign-up creates all five rows in
  one transaction (a forced mid-failure leaves **zero** rows — use a
  too-long `name` to fail after the account insert); duplicate live phone →
  uniform `E_AUTH` (same body as wrong password); invalid phone → `E_SCHEMA`;
  tombstoned account (`deleted_at` set): sign-in refused with `E_AUTH` **and** its
  phone cannot re-register as a *new* account until week 7's deletion window (assert
  `E_AUTH`); 6th attempt from one IP → `E_RATE`; a `deleted_at` account is skipped
  by lookup; the response never contains the phone number.
- [ ] **Step 2: Run to watch them fail.**
- [ ] **Step 3: Implement** — per-IP and per-phone limiters; E.164 normalize first,
  reject `E_SCHEMA`; lookup `where phone = ? and deleted_at is null`; hash with a
  fresh 16-byte salt; `Main.kt` wires `accountRoutes`; `backend.v1.yaml` updated in
  this commit.
- [ ] **Step 4: Run to watch them pass.**
- [ ] **Step 5: Commit**

```powershell
git add services/backend spec/openapi/backend.v1.yaml
git commit -m "feat(backend): phone and password accounts with a household of one"
```

---

### Task 4: Optional SMS OTP with the verified-wins clash policy

**Files:**
- Create: `services/backend/src/main/kotlin/com/hopa/backend/accounts/SmsPort.kt`
- Create: `services/backend/src/main/kotlin/com/hopa/backend/accounts/OtpRoutes.kt`
- Modify: `services/backend/src/main/kotlin/com/hopa/backend/ServerConfig.kt` (`smsEnabled`, `sms: SmsPort?`)
- Modify: `spec/openapi/backend.v1.yaml` (`/v1/session/otp`, `/v1/session/otp/verify`)
- Test: `services/backend/src/test/kotlin/com/hopa/backend/accounts/OtpTest.kt`

**Interfaces:**
- Consumes: Task 3's `AccountService` internals; `Phones`, `RateLimiter`.
- Produces: `interface SmsPort { fun send(to: String, code: String) }` with
  `LogSmsPort` (logs the code at INFO — dev only) as the only implementation;
  `POST /v1/session/otp {phone}` → 202 (always, uniform); `POST
  /v1/session/otp/verify {phone, code, platform, name, publicKey}` → tokens. Codes:
  6 digits, `SecureRandom`, TTL 5 min, single use, 3 attempts per code, 3 codes per
  phone per hour.

- [ ] **Step 1: Write the failing tests** — with the flag **off**: both routes →
  `E_UNSUPPORTED` and the fake port records nothing; with it **on** (fake port):
  request → verify round-trip issues tokens for a signed-in device; a used code
  fails second time; an expired code fails; a wrong phone gets 202 + silence (no
  enumeration); **clash**: an unverified account exists on the number, a verified
  OTP login binds to *that* account and stamps `verified_at` (not a second
  account); the fake port's number argument is the normalized E.164.
- [ ] **Step 2: Run to watch them fail.**
- [ ] **Step 3: Implement** — codes live in an in-memory map keyed by phone hash
  (single container, documented in the runbook like the other process-local
  limits); uniform timing on verify regardless of phone existence.
- [ ] **Step 4: Run to watch them pass.**
- [ ] **Step 5: Commit**

```powershell
git add services/backend spec/openapi/backend.v1.yaml
git commit -m "feat(backend): optional SMS OTP with verified-wins takeover"
```

---

### Task 5: Admin session and lookup endpoints, access log

**Files:**
- Create: `services/backend/src/main/kotlin/com/hopa/backend/admin/AdminSessionRoutes.kt`
- Create: `services/backend/src/main/kotlin/com/hopa/backend/admin/LookupRoutes.kt`
- Test: `services/backend/src/test/kotlin/com/hopa/backend/admin/LookupTest.kt` (Testcontainers)

**Interfaces:**
- Consumes: Task 1's `admins`/`admin_sessions` + definer functions; Task 2's
  `Passwords`; slice A Task 6's `AdminAuth` token lookup (land in either order —
  this task defines the *routes*, that one the *lookup*; keep signatures identical
  to its Interfaces block).
- Produces: `POST /v1/admin/session {email, password}` → 8 h opaque token
  (SHA-256 at rest); `GET /v1/admin/households?query=` → `[{householdId,
  memberId, displayName, phone, verified, accountStatus}]` resolving by E.164
  phone (direct `accounts` read), by member uuid or household uuid (definer
  functions). (`GET /v1/admin/devices?household=` with live presence is owned by
  slice A Task 6; the lookup page calls both.) Every response row containing a
  phone is preceded by `EventLog.event("admin.pii.read", adminId, query)`.

- [ ] **Step 1: Write the failing tests** — seeded admin signs in, wrong password →
  uniform `E_AUTH`, 6th attempt → `E_RATE`; lookup by phone / by member id / by
  household id each return the same household; a **wrong-looking** uuid (not a
  member) returns `[]` not 500; a household device token on `/v1/admin/*` →
  `E_AUTH`; the JSON event log holds one `admin.pii.read` line per request; the
  definer functions are called as `hopa_app` (prove a plain tenant `select` on
  `members` still returns 0 rows without `SET LOCAL`).
- [ ] **Step 2: Run to watch them fail.**
- [ ] **Step 3: Implement** and add the routes to `backend.v1.yaml`.
- [ ] **Step 4: Run to watch them pass.**
- [ ] **Step 5: Commit**

```powershell
git add services/backend spec/openapi/backend.v1.yaml
git commit -m "feat(backend): admin session and household lookup, PII access log"
```

---

### Task 6: Console pages

**Files:**
- Create: `services/backend/src/main/kotlin/com/hopa/backend/console/ConsoleRoutes.kt`
- Create: `services/backend/src/main/kotlin/com/hopa/backend/console/ConsoleViews.kt`
- Modify: `services/backend/build.gradle.kts` (`ktor-server-html-builder`), `spec/openapi/README.md`
- Test: `services/backend/src/test/kotlin/com/hopa/backend/console/ConsoleTest.kt`

**Interfaces:**
- Consumes: Task 5's routes; slice A Task 6's diag route; `/v1/health`.
- Produces: `/console/sign-in`, `/console` (health + queue depth),
  `/console/lookup?query=` (member-id, household-id, phone, connected devices),
  `/console/device/{id}` with a diag sender form (`diag.ping|state|reset`). Pages
  are kotlinx.html server-rendered; session via the admin token in a
  `Secure`/`HttpOnly`/`SameSite=Strict` cookie; no client JS beyond a plain form.

- [ ] **Step 1: Write the failing tests** — unauthenticated `/console` redirects to
  `/console/sign-in`; a signed-in admin's lookup page contains the household id and
  the masked-last-3 phone (`…4567`); the page for a household lists both devices
  with their `online` state; a diag form post returns 202 and the page shows the
  audit confirmation; **no page template contains any task body, turn, or chat
  field** (assert the rendered HTML of every route against a fixture household
  with tasks and turns).
- [ ] **Step 2: Run to watch them fail.**
- [ ] **Step 3: Implement** — one `ConsoleViews` object per page; the diag sender is
  the last-built feature and may slide to week 3 per the flex order.
- [ ] **Step 4: Run to watch them pass.**
- [ ] **Step 5: Commit**

```powershell
git add services/backend spec/openapi/README.md
git commit -m "feat(backend): team console - sign-in, health, lookup, diag sender"
```

---

### Task 7: Android — sign-in and the two modes

**Files:**
- Create: `apps/android/app/src/main/kotlin/com/hopa/app/account/SignInScreen.kt`
- Create: `apps/android/app/src/main/kotlin/com/hopa/app/account/AccountViewModel.kt`
- Modify: `apps/android/app/build.gradle.kts` (`buildConfigField` from `local.properties`), the app's settings store and launch navigation (the `backendUrl == null` path `LaunchPairingTest` already covers)
- Test: `apps/android/app/src/test/kotlin/com/hopa/app/account/SignInViewModelTest.kt`, `LaunchModeTest.kt`

**Interfaces:**
- Consumes: Task 3's `/v1/accounts` and `/v1/session`; `core/backend`'s
  `BackendCredentials` store; the existing settings snapshot.
- Produces: a sign-in screen (phone + password, sign-up toggle) that stores
  credentials and the unwrapped content key (the phone generated it — keep it in
  the app's private store, never in `BackendCredentials`); `AccountViewModel.signIn
  / signUp(phone, password)`; `BuildConfig.BACKEND_URL` (null in release, from
  `local.properties` `hopa.backendUrl` in debug); launch routing: null → local-only
  home with pairing UI hidden, set → sign-in/paired path. No bridge/LAN code.

- [ ] **Step 1: Write the failing tests** — ViewModel against a MockWebServer:
  sign-up posts the normalized `+972…` phone and a 32-byte `selfWrappedKey`, stores
  tokens; a uniform `E_AUTH` surfaces as one message (no phone-vs-password
  distinction); `LaunchModeTest`: `backendUrl = null` → local-only home, no pairing
  entry point; configured-but-unreachable → local-only home with a banner, not a
  crash (Review Focus line 5).
- [ ] **Step 2: Run to watch them fail** — Run:
  `apps\android\gradlew.bat -p apps/android :app:testDebugUnitTest --tests "*SignIn*" --tests "*LaunchMode*"`
- [ ] **Step 3: Implement** — the phone generates its XDH keypair + content key at
  sign-up and wraps for itself with `KeyWrap.wrapForDevice` (slice A Task 2).
- [ ] **Step 4: Run to watch them pass.**
- [ ] **Step 5: Commit**

```powershell
git add apps/android/app
git commit -m "feat(android): phone-and-password sign-in and the two modes"
```

---

### Task 8: Android — camera QR scan and vouch

**Files:**
- Create: `apps/android/app/src/main/kotlin/com/hopa/app/pair/ScanScreen.kt`
- Create: `apps/android/app/src/main/kotlin/com/hopa/app/pair/VouchViewModel.kt`
- Modify: `apps/android/app/build.gradle.kts` (CameraX + ML Kit `barcode-scanning`), `AndroidManifest.xml` (`CAMERA`), the pairing input screen (paste fallback stays)
- Test: `apps/android/app/src/test/kotlin/com/hopa/app/pair/VouchViewModelTest.kt`

**Interfaces:**
- Consumes: `PairingLink` (parses `hopa://pair?…`); slice A Task 4's
  `/v1/pair/vouch`; `KeyWrap`; the pending session's PC public key.
- Produces: `ScanScreen` (CameraX preview + ML Kit, fires on the first matching
  `hopa://` barcode); `VouchViewModel.vouch(link: PairingLink)`: resolves the
  pending session by the secret in the QR (`POST /v1/pair/pending/resolve
  {sessionSecret}` — there is no id route), shows name + fingerprint
  `sha256(publicKey)[0..8]`, then POSTs the vouch with
  `KeyWrap.wrapForDevice(contentKey, pcPublicKey, phonePrivateKey)`.

- [ ] **Step 1: Write the failing tests** — a scanned link resolves to a confirm
  state with the PC name + fingerprint; confirm POSTs the vouch with a wrap the
  test unwraps with the PC key to the known content key; a link for an
  expired/claimed session surfaces `E_SCHEMA` and stays on the scan screen; the
  paste path (`PairInput`) still reaches the same confirm state.
- [ ] **Step 2: Run to watch them fail.**
- [ ] **Step 3: Implement** — camera failure (no permission, no camera) falls back
  to paste with a hint, never blocks the flow (flex rule: camera may slide, paste
  cannot).
- [ ] **Step 4: Run to watch them pass.**
- [ ] **Step 5: Manual (evidence)** — one real camera scan against the deployed
  instance recorded in `docs/project/evidence/B2-accounts.md` (§8 row 3).
- [ ] **Step 6: Commit**

```powershell
git add apps/android/app docs/project/evidence/B2-accounts.md
git commit -m "feat(android): camera QR scan vouches the PC with a wrapped key"
```

---

### Task 9: Housekeeping and the evidence file

**Files:**
- Modify: `services/backend/README.md` (rewrite to the shipped Kotlin/Ktor reality; drop the Python sketch)
- Create: `docs/project/evidence/B2-accounts.md` (§8 table of `week2.md`, one row per check, commands + trace ids + timestamps)

- [ ] **Step 1: Rewrite the README** — what the service is, how to run it
  (`apps\android\gradlew.bat -p services/backend test`, Dockerfile, env vars
  including the new `HOPA_ADMIN_EMAIL`, `HOPA_ADMIN_PASSWORD`, `HOPA_SMS_ENABLED`),
  and the module map. Cross-check with `py -3 tools/checks/check-links.py`.
- [ ] **Step 2: Create the evidence skeleton** with all nine §8 rows unticked; tick
  each as its proof lands; `python tools/scripts/plan-status.py --plan
  docs/project/plan/backend` stays green.
- [ ] **Step 3: Commit**

```powershell
git add services/backend/README.md docs/project/evidence/B2-accounts.md
git commit -m "docs(backend): README matches the service; week 2 evidence file"
```

---

### Task 10: Review follow-ups from the lane A review

**Files:**
- Modify: `apps/android/app/src/main/kotlin/com/hopa/app/link/` (the `BackendClient` seam the app already holds)
- Modify: `apps/windows/sidecar/src/main/kotlin/com/hopa/sidecar/Main.kt` (`backend.pair` command)
- Modify: `apps/windows/README.md`
- Test: an Android unit test asserting a `diag.*` delivery is acked; a sidecar test asserting a second `backend.pair` while one is in flight is refused

Three findings from the whole-branch review of lane A, deferred here because
none of them blocks a lane A exit check (full analysis in the review thread):

1. **diag frames are dropped on Android.** `BackendClient.dispatch` returns
   silently when `onDiagnostic` is null (the Android default), while the server
   has already marked the task `delivered` and the console got a 202. Wire the
   Android handler to the diag seam and ack each frame, so exit check #7's
   "arrives" becomes true at the app level, not just the socket level.
2. **Concurrent `backend.pair` commands race.** Two simultaneous commands each
   run a full admission and both write `backend-session.json`; last writer wins
   and the loser's pending session stays live until its TTL. Guard the command:
   while an admission is in flight, reply `ok=false` ("already pairing")
   immediately instead of starting a second one.
3. **Turn goals are no longer logged.** `Engine` logs `turn <id> in
   <conversation>` without the goal (privacy, landed with the lane A gateway
   work). Note the redaction in `apps/windows/README.md` so operators do not
   mistake it for lost output.

- [ ] **Step 1: Write the failing tests** — Android: a delivered `diag.ping`
  reaches the app handler and sends a `task.ack`; Sidecar: the second
  `backend.pair` while the first is unresolved answers `ok=false` and mints no
  second pending session.
- [ ] **Step 2: Run to watch them fail.**
- [ ] **Step 3: Implement** the Android `onDiagnostic` wiring + ack, the
  sidecar in-flight guard, and the README note.
- [ ] **Step 4: Run to watch them pass.**
- [ ] **Step 5: Commit**

```powershell
git add apps/android/app apps/windows
git commit -m "fix(clients): ack console diagnostics and guard concurrent pairing"
```

---

## Slice boundary

Sync v0 is **not** in this slice: B writes its one-page mini-design Monday
(tables, cursors, tombstones, ciphertext rules — the bridge store's semantics) and
it becomes `tasks/week2-sync.md` before any handler. It is the protected exit
check and never slides. Slice A (`tasks/week2a.md`) owns the key model, admission,
diag and the sidecar; this slice's Tasks 1–2 unblock its Tasks 4–6.
