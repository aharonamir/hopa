# Week 2 · 22–26 Nov 2026: accounts, PC admission, console, sync v0

Status: **lane A implemented, 2026-11-22** — branch `backend-week2-A`
(pushed to `amir-fork`), HEAD `b92ee70c`. Lane B and sync v0 remain planned.
Written 2026-11-22. Parent: [`weekly-plan.md`](weekly-plan.md),
tracked in [#32](https://github.com/agents-demo/hopa/issues/32). Design source:
[`docs/superpowers/specs/2026-11-22-pairing-accounts-design.md`](../../../superpowers/specs/2026-11-22-pairing-accounts-design.md)
(decisions below argue from it, not from this file). Verification guide:
[`week2-lane-a.html`](week2-lane-a.html).

The week replaces the operator bootstrap flow with a real user: the phone signs up
with a phone number and password and becomes the household's first device, the PC
joins by a QR the phone scans and vouches for, and the team gets a console that
looks up households and fires diagnostic tasks. Sync v0 — the original centrepiece —
stays the protected exit check.

## 1. Goal

A user signs up on the phone (E.164 number + password), admits their Windows PC by
scanning the PC's QR with the camera, and the two devices exchange an
end-to-end-encrypted task in each direction; a turn typed on either device's chat
syncs to the other in ≤ 2 s (target); the console shows the household, the member,
the phone number and both devices' live state, and can send a `diag.ping` that
arrives. A revoked device is cut off within 5 s.

Exit check: §8.

## 2. Non-goals

- No household invites, roles or approvals (weeks 4–5); no attachments or shared
  threads (week 6); no third-device admission exercises (the flow is generic but
  only phone→PC is proven).
- No SMS provider: `SmsPort` ships with a log-only implementation behind
  `HOPA_SMS_ENABLED=false`. OTP endpoints exist but no vendor contract is signed.
- No recovery-code endpoints or UI — only the crypto primitive (design §2).
- No console real-task composition, ever (design §4); no conversation contents in
  the console, ever.
- No LAN bridge path in the Android app. The bridge stays a standalone eval tool.
- No changes to the relay (`services/relay`) — it runs unchanged for shipped
  clients.

## 3. Decisions taken

All from the design doc; the table records the ones an implementer must not
re-open.

| Decision | Value | Why |
|---|---|---|
| User identity | `accounts.id` UUID; phone E.164 unique column, argon2id hash, `verified_at` null while SMS is off | Numbers change and recycle; they are login lookup only, never keys, never on the wire |
| Member link | `members.account_id` nullable | Week-4 invites join an existing account to a household without re-keying |
| SMS OTP | `HOPA_SMS_ENABLED` (default false), log-only `SmsPort`; alternative credential; verified login takes over an unverified account | No vendor surface until wanted; squatters never block the real owner |
| Household key | One 256-bit content key per household, stored only as `household_key_wraps` rows (X25519 ECDH → HKDF → AES-GCM) | ADR-0026; the server is content-blind by construction |
| PC admission | Unauthenticated pending session (5/min/IP, 5-min TTL) + authenticated vouch from the phone + single-use claim with tokens and wrapped key in one transaction | A QR never becomes a device unless an authenticated device says yes |
| QR content | `hopa://pair?v=1&s=<secret>&u=wss://…` — no household id | Binding happens at vouch; no tenant ids leak to a screen |
| Console tasks | `diag.ping`, `diag.state`, `diag.reset` only; plaintext, admin-only, audit-logged | The console holds no household keys; the exemption is written into the key model |
| Admin access | Own `admins` table, own sessions; `SECURITY DEFINER` lookups for tenant rows; phone-number reads logged | An operator is never a household member; RLS `FORCE` stays untouched |
| PC client | In the Kotlin sidecar, reusing the JVM `backend` client module | ADR-0021's shape; one client library for Android and Windows |
| Android QR input | CameraX + ML Kit scan; paste of the `hopa://` string stays as fallback | Camera is the user flow; paste keeps the flow usable if camera work slips |
| Android modes | `backendUrl` configured → backend; null → local-only | The unconfigured state already exists; dev pointing via debug `buildConfigField` from `local.properties` |

## 4. API additions (spec changes land with their handlers)

| Route | Auth | Body → Result |
|---|---|---|
| `POST /v1/accounts` | none (rate-limited) | `{phone, password, platform, name, publicKey, alg, selfWrappedKey, pushToken?}` → account + household-of-one + member + device + wrap row + tokens, one transaction |
| `POST /v1/session` | none (rate-limited) | `{phone, password, platform, name, publicKey, pushToken?}` → signed-in device + tokens (no history without a wrap) |
| `POST /v1/session/otp` | none | `{phone}` → code sent via `SmsPort` (log-only until a vendor exists) |
| `POST /v1/session/otp/verify` | none | `{phone, code, platform, name, publicKey}` → verified login; binds and stamps `verified_at` on an unverified account |
| `POST /v1/pair/pending` | none (5/min/IP) | `{platform, name, publicKey, alg}` → `{sessionId, secret, qr, expiresAt}` |
| `POST /v1/pair/pending/resolve` | none (30/min/IP) | `{sessionSecret}` → `{sessionId, state, name, platform, publicKey, fingerprint, expiresAt}`; the PC polls by the secret it minted |
| `POST /v1/pair/vouch` | device Bearer | `{sessionSecret, wrappedKey}` → binds household + member; stores the wrap; session becomes vouchable |
| `POST /v1/pair/claim` | none | extended: a pending session claim returns tokens, `wrappedKey` and the voucher's `voucherPublicKey` for the PC's key |
| `GET /v1/devices` | device Bearer | the household's device list |
| `DELETE /v1/devices/{id}` | device Bearer | revoke: token family + wrap row, 5 s cut-off (exit check) |
| `POST /v1/admin/session` | none (rate-limited) | admin sign-in (email + password) |
| `GET /v1/admin/households?query=` | admin Bearer | lookup by phone, member-id or household-id (access-logged) |
| `GET /v1/admin/devices?household=` | admin Bearer | device list + live presence + `last_seen_at` |
| `POST /v1/admin/devices/{id}/diag` | admin Bearer | `{household, type: diag.ping \| diag.state \| diag.reset, body?}` → server-authored task |

Envelope: `diag.ping`, `diag.state`, `diag.reset` join the allowed types; they are
the only plaintext bodies. Everything else stays as frozen in week 1 (§4 of
[`week1.md`](week1.md)); no version bump is needed because adding types is not a
breaking change — unknown types are already refused with `E_UNSUPPORTED`.

## 5. Data model additions (V2 migration)

| Table | Key columns | Notes |
|---|---|---|
| `accounts` | `id`, `phone` unique, `password_hash`, `verified_at`, `created_at`, `deleted_at` | **not** tenant-scoped; RLS off, read only by auth/admin paths |
| `household_key_wraps` | `household_id`, `device_id`, `wrapped_key`, `wrapped_at` | tenant-scoped, RLS on; deleted on revoke |
| `admins` | `id`, `email` unique, `password_hash`, `role`, `created_at` | not tenant-scoped; first row seeded from `HOPA_ADMIN_EMAIL`/`HOPA_ADMIN_PASSWORD` at boot |
| `admin_sessions` | `id`, `admin_id`, `token_hash`, `expires_at` | 8 h, opaque, SHA-256 at rest |
| `pending_pairings` | `id`, `code_hash`, `platform`, `name`, `public_key`, `alg`, `household_id` null, `member_id` null, `vouched_at`, `expires_at`, `claimed_at`, `wrapped_key` null, `voucher_device_id` null | single use, 5-min TTL; binding written by vouch; the wrap parks here until a device exists, and the voucher's device id lets claim return its public key |
| `members` (alter) | + `account_id uuid null references accounts` | null for operator-paired members |
| `tasks` (alter) | + `type text not null default 'task.create'`; `from_device` nullable | server-authored `diag.*` rows have no sending device |

Follow-up migrations landed by lane A (V2 is the base schema above):
`V3__pending_wrap.sql` adds `pending_pairings.wrapped_key` (R25) and
`V4__voucher_key.sql` adds `pending_pairings.voucher_device_id` (R28), both
nullable and additive. `tasks.type` / nullable `from_device` are in V2 (R1, R11).

Plus four `SECURITY DEFINER` functions owned by the non-login `hopa_admin`
(`BYPASSRLS`) role, each returning minimum columns and audit-logged by the caller:
`admin_resolve_member(member uuid)` and `admin_resolve_household(household uuid)`
return `(household_id, member_id, display_name)`; `admin_resolve_phone(phone text)`
also returns `account_id` (four columns); `admin_list_devices(household uuid)`
returns `(device_id, platform, name, last_seen_at, revoked_at)`. The app role has
no `BYPASSRLS` and no blanket tenant GUC.

`V5__admin_account_id.sql` adds `account_id` to `admin_resolve_member` and
`admin_resolve_household` so a lookup by member or household id can also return
the account fields (phone, verified, account status).

Sync v0's tables (`threads`, `turns`, `device_cursors` — naming to be settled by
Monday's mini-design) land in the same migration only if that design is agreed
Monday; otherwise a `V3` follows mid-week.

## 6. Work plan

| Day | Lane A (Amir) | Lane B (Batya) |
|---|---|---|
| Sun 22 | Read the design; ADR-0026 drafted | Read the design; `V2__accounts.sql` drafted |
| Mon 23 | **ADR-0026 agreed**; `KeyWrap` primitives (X25519+HKDF+AES-GCM, recovery-code derivation) with unit tests | V2 migration + Testcontainers tests (RLS on `household_key_wraps`, non-tenant accounts/admins, definer functions); E.164 + argon2 utilities; **sync v0 mini-design written** |
| Tue 24 | pending-session + vouch + claim-with-wrap endpoints (spec in the same PR) | accounts sign-up/sign-in endpoints (rate limits, no enumeration, uniform `E_AUTH`); admin seed + `POST /v1/admin/session` |
| Wed 25 | admin presence + diag-send endpoints; `diag.*` in the validator; server-authored tasks | console health page on the admin API; lookup page; access log |
| Thu 26 | device list / revoke / refresh (original item); admission end-to-end with a real PC — sidecar: pending session, QR render, poll, claim, unwrap | **sync v0 implementation (protected)**; console lookup wired to live presence |
| Fri 27 | sidecar gateway envelopes both directions; wrap-deleted-on-revoke | diag sender UI; Android sign-in screen + local-only mode + camera QR scanner |

Flex order (what slides to week 3 first): sidecar polish (fallback: operator QR) —
then diag sender UI — then camera scanner (paste covers) — then Android polish.
Sync v0 never slides.

## 7. Risks

| Risk | Effect | Mitigation | Owner |
|---|---|---|---|
| Week 2 was already the heaviest week; adding admission + console overloads it | Sync v0 slips and the demo is at risk | Flex order above; operator QR as demo fallback; sync v0 never slides | both |
| Sidecar PC work is new surface (no backend code exists there today) | The admission exit check fails late | Reuse the JVM `backend` client module Android already uses; lane A owns it Thu–Fri; fallback path is already deployed | A |
| Argon2id on the free tier | Slow sign-up under load | Tune cost parameters once, measure in CI; it is a sign-in-path cost, not per-message | B |
| Camera + ML Kit is a new Android dependency | Build size, permission friction, device variance | Paste fallback ships in the same PR; camera is additive | B |
| `SECURITY DEFINER` functions widen access by mistake | Tenant leak — the one bug that ends the beta | Four functions return minimum columns only; CI calls them as the app role and asserts the shape; every call is audit-logged | B |
| Plaintext `diag.*` becomes a smuggling channel | Server-readable task bodies reappear | Validator allows plaintext for the three `diag.*` types only; admin-auth required; audit per send | A |
| QR capability: a photographed QR claimed in the vouch→claim window | The claimant obtains household **device tokens** (not the content key, which stays wrapped to the PC) | 5-min TTL, the phone shows the key fingerprint before vouching, and the residual is accepted for the demo (R32); a second claim secret, returned only to the PC, lands with week-6 admission | both |
| `hopa_admin` needs superuser provisioning once | V2 fails at boot on a deploy role that cannot create a `BYPASSRLS` role | Probe the role at migration time with an actionable error; deploy guide documents the superuser run; the runtime role is not superuser (R21/R21a) | A |
| Phone-number enumeration via sign-up errors | Account discovery before any SMS | Uniform `E_AUTH` for wrong phone and wrong password; per-IP and per-phone rate limits | B |
| Sync v0 redesign mid-week (mini-design slips past Monday) | B loses Thursday | The mini-design is one page, Monday-only; if it slips, sync v0 keeps the bridge store's rules verbatim and the design lands as a PR comment | B |

## 8. Exit check (Fri 27 Nov)

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

## 9. Lane A execution record (22 Nov 2026)

Lane A ran subagent-driven: one implementer per task, a fresh task reviewer per
diff, a final whole-branch review, then one fix wave. Task 0 is slice B Task 1
pulled forward; Tasks 1–8 are lane A. 29 commits from `f9682f69` to `b92ee70c`
on `backend-week2-A`, the last one the post-review fix (§9.3). Nothing is
deployed and no exit check is claimed without the evidence named here.

### 9.1 Task status

| Task | Scope | Commits | Proof |
|---|---|---|---|
| 0 | V2 accounts/wraps/admins/pending pairings + the four definer functions | `68bfb7d1`, `07229282`, `f2f81dd2` | `V2SchemaTest` (10), full module suite |
| 1 | ADR-0026 + ADR-0022 accepted | `054fd423`, `3fc7b944` | ADR review, `check-links` 0 |
| 2 | `KeyWrap`, `Argon2`, RFC 7748 key helpers | `0b7ef05b`, `0cbfa980` | 97 `:backend` tests, RFC 7748 vectors |
| 3 | Pending sessions: mint, resolve-by-secret | `40197f2f`, `aeb5e796` | `PendingPairingTest` (16) |
| 4 | Vouch, claim-with-wrap, parked wrap (V3) | `7616ddcb` | `AdmissionTest` (13) |
| 5 | Device list + 5 s revocation | `6523d033`, `d436b8ca`, `46cdd1b8` | `DeviceRevokeTest`, `KickTeardownTest` |
| 6 | `diag.*`, admin auth, presence, audit | `92272bd2`, `400126b1` | `DiagSendTest` (16) |
| 7 | Sidecar admission, QR, poll, claim, unwrap (V4) | `2e2914e2`, `93272f03`, `8d059231` | sidecar + core admission tests |
| 8 | Sidecar encrypted gateway both directions | `60a1e3be`, `9c0176c0` | `BackendGatewayTest`, `PayloadCipherTest` |
| — | Final-review fix wave | `99416bd4`, `e3c9be38`, `d4515c5d`, `809c97e2`, `118749e0` | see §9.5 |
| — | Post-review fix (`b92ee70c`) | `b92ee70c` | claim-alg test; §9.3 post-review rows |

### 9.2 Decisions taken during execution

These are now part of the plan; Lane B and sync v0 build on them.

| Area | Decision | Why it deviated from the written slice |
|---|---|---|
| Order | Slice B Task 1 (V2) ran first as Task 0 | Lane A Tasks 4–6 depend on the schema |
| Schema | V3 adds `pending_pairings.wrapped_key`; V4 adds `voucher_device_id` | A wrap is FK'd to `devices`, which exists only at claim; static-static X25519 needs the wrapper's public key at unwrap |
| Admission | No `GET /v1/pair/pending/{id}`; both devices resolve by the secret | The id cannot satisfy the pending RLS (no tenant, no token hash) and the PC already holds the secret |
| Polling | Resolve is 30/min/IP; the sidecar polls every 5 s, backing off on 429 | 5/min could not sustain a 5-minute poll; 2 s would burn the budget |
| Keys | Canonical public keys are RFC 7748 octets; ECDH is BouncyCastle-only | The JDK `XECPublicKey.u` path and Android's SPKI fallback produced opposite byte orders; BC also removes the API-33 floor |
| Admin RLS | The four definer functions are owned by a non-login `hopa_admin` (`BYPASSRLS`) role; the migration borrows membership, then revokes it and refuses a non-superuser that still has `SET` or `ADMIN OPTION` | `FORCE RLS` binds the table owner, so definer functions owned by the migration role saw zero rows |
| Admin API | `GET /v1/admin/devices` calls `admin_list_devices`; a diag request names its `household` | Use the containment mechanism that was built; avoid another cross-tenant lookup |
| Tasks | `tasks.type` column; `from_device` nullable; `"server"` is the sentinel; terminal states are terminal | Diag envelopes need their own type, and a late `ack` must not downgrade `done` |
| Payloads | Encrypted bodies are `{"sealed": "<base64url(IV‖ct‖tag)>"}` under the household key | One convention both clients can implement; `diag.*` stays the only plaintext |
| Reuse | A reused refresh token revokes the device, deletes its wrap and kicks the live socket; `rotate` refuses a revoked device under a row lock | Reuse detection previously only cleared the cache, and rotation could race a revoke |
| Client seams | `onTask` handles `task.create`; `onDiagnostic` handles `diag.*` | Keeps the Android task runner from treating a diagnostic as a goal-less task |
| Sidecar entry | `--pair-backend <url>` and a `backend.pair` protocol command | `admit()` had no production caller, so the PC could not be admitted |

### 9.3 Review findings that changed the code

| Task | Finding | Resolution |
|---|---|---|
| 0 | Definer functions returned no rows to `hopa_app` under `FORCE RLS` | `hopa_admin` owner + attribute/no-SET/no-ADMIN-OPTION guards |
| 1 | Wrapped-key length said 48 bytes; recovery blob and wrap inputs under-specified | 60/76-byte layouts, raw octets, HKDF salt/info, empty AAD pinned |
| 2 | **Critical:** JDK and Android produced opposite public-key byte orders; self-wrap failed | RFC 7748 octets everywhere; BC-only ECDH; fixed vectors |
| 3 | Resolve at 5/min would 429 the PC's poll | 30/min + backoff, precedence tests |
| 5 | A kick could be lost between auth and attach; `Session` leaked as a mutable map key; close was not bounded; tombstone not atomic | Tombstone in `attach`/`join`, referential equality, unconditional cancel after a 1 s grace, one lock |
| 6 | Diag trace spans were dropped (`"server"` into a uuid column); audit was written after delivery | Span device = target; audit before deliver/wake |
| 7 | Pairing secret written to protocol stdout; credential `toString` unredacted | stderr/UI seam; redacted `toString`; atomic owner-only store; PNG deleted |
| 8 | **Critical:** the shared link never dispatched `diag.*`; the goal was logged; a wedged turn held the mutex; a failed reply was not acked; cancel cross-talk | `onDiagnostic` + dispatch, log redaction, turn gate + timeout, reply-before-ack, id-aware cancel |
| Final | No PC admission entry point; Android diag regression; reuse not a hard cut; rotate/revoke race; concurrent host/backend turns; unused definer; ack downgraded `done`; wake failure returned 500 | All fixed in the final wave (§9.1) |
| Post-review (`b92ee70c`) | The pending claim wrote `request.alg` while the minted row was authoritative for every other field; both validate to `x25519` today, so no live behavior changed | `ParkedRow` now carries the minted `alg` and the claim writes it; a direct-claim test pins that `alg` cannot be swapped |
| Post-review (retracted) | "Trim `publicKey` from the resolve response" — withdrawn after implementation analysis | The vouch wrap needs the PC public key (`KeyWrap.wrapForDevice`), and resolve is its only channel to the phone (slice B Task 8); a QR-carried key would reach a photographed-QR attacker just the same, so the residual stays as accepted (week-6 second claim secret) |
| Post-review (deferred) | Android drops `diag.*` at the link layer while the server records `delivered`; concurrent `backend.pair` commands race the session file; turn goals no longer appear in sidecar logs | Moved to slice B Task 10 (`week2b.md`) |

### 9.4 Deferred and parked

- **Manual deployed PC admission E2E** — one real phone + one real PC, camera scan,
  one encrypted task each way, trace ids in `docs/project/evidence/B2-accounts.md`.
  The entry point now exists; this is the only Lane A exit proof still missing.
- **Lane B** — accounts/OTP, admin sign-in issuer, household lookup, console,
  Android sign-in/local mode/camera scan, sync v0, `services/backend/README.md`,
  plus the review follow-ups in slice B Task 10.
- **Parked design items** — the QR capability residual (accepted for the demo; a
  second claim secret lands with week-6 admission), server-side `sealed`
  enforcement (sync v0 hardening), expired-row/limiter sweeps and `X-Forwarded-For`
  trust (single container; verify the edge), duplication/secret-zeroing cleanup.
- **Minor** — stale comments in `PostgresHarness` about the old migration path.

### 9.5 Verification at `118749e0`

| Command | Result |
|---|---|
| `apps\android\gradlew.bat -p services/backend :test --rerun-tasks` | BUILD SUCCESSFUL, 173 tests (172 at `118749e0`, +1 claim-alg test at `b92ee70c`) |
| `apps\android\gradlew.bat -p apps/android :backend:test --rerun-tasks` | BUILD SUCCESSFUL, 97 tests |
| `apps\android\gradlew.bat -p apps/windows/sidecar test --rerun-tasks` | BUILD SUCCESSFUL, 42 sidecar tests + dependency suites |
| `py -3 tools/checks/check-links.py` | 0 broken links |
| `python tools/scripts/plan-status.py --plan docs/project/plan/backend` | green |

### 9.6 Exit-check status

- **Proven by tests:** #2 (revoke cuts tokens, wrap and the live socket within 5 s),
  #7 (offline diag queues, wakes once via FakeFcm, audit names the admin),
  #8 (the 1,000-attempt isolation suite now includes wraps and pending pairings).
- **Partially proven:** #3 — admission, unwrap and encrypted tasks both ways are
  tested against MockWebServer/fake links; the real camera scan and deployed
  round-trip are the deferred manual E2E.
- **Lane B:** #1, #4, #5, #6, #9 (account/sync/console/Android paths and the
  week-log grep).

## 10. Checklist

Every box is a change, a test or an artifact, not a meeting. Slices:
[`tasks/week2a.md`](tasks/week2a.md) (lane A), [`tasks/week2b.md`](tasks/week2b.md)
(lane B); sync v0's slice is written Monday from the mini-design.

### W2.0 Design and ADR — Monday

- [x] `docs/design/decisions/0026-household-content-key.md` written and indexed in
      `docs/design/decisions/README.md`. *(Lane A, `054fd423`/`3fc7b944`.)*
- [x] ADR-0022 status flipped to accepted; stale version/module references fixed.
      *(Lane A, `054fd423`/`3fc7b944`.)*
- [ ] Sync v0 mini-design (one page: tables, cursors, tombstone rules, ciphertext)
      agreed and linked here.

### W2.1 Schema and utilities — Monday

- [x] `V2__accounts.sql`: accounts, household_key_wraps, admins, admin_sessions,
      pending_pairings, `members.account_id`; RLS on tenant tables; the four
      `hopa_admin`-owned definer functions. *(Lane A Task 0, `68bfb7d1`; V3/V4 followed.)*
- [x] Testcontainers: migration applies fresh and idempotent; app-role reads of
      `household_key_wraps` outside the tenant return zero rows; definer functions
      return exactly their minimum columns. *(`V2SchemaTest`, 10 tests.)*
- [x] E.164 normalization + argon2id hashing utilities with unit tests.
      *(Lane B Task 2, `c2d33f9a`; `AccountsUtilTest`.)*
- [x] Admin seed from `HOPA_ADMIN_EMAIL`/`HOPA_ADMIN_PASSWORD` at boot, upsert.
      *(Lane B Task 2, `c2d33f9a`.)*

### W2.2 Key wrapping — Monday/Tuesday

- [x] `KeyWrap`: X25519 ECDH → HKDF-SHA256 → AES-256-GCM wrap/unwrap; round-trip
      unit tests; tamper test (flipped bit → refusal); recovery-code derivation
      (Argon2id → AES-GCM) unit-tested, endpoints deferred. *(Lane A Task 2,
      `0b7ef05b`/`0cbfa980`; RFC 7748 vectors.)*

### W2.3 Accounts and sessions — Tuesday

- [x] `POST /v1/accounts` and `POST /v1/session` per §4; uniform `E_AUTH`; rate
      limits per IP and per phone; `spec/openapi/backend.v1.yaml` updated in the
      same PR. *(Lane B Task 3, `5d671046`; `AccountRoutesTest`.)*
- [x] `POST /v1/session/otp` + `/verify` behind `HOPA_SMS_ENABLED` with the
      log-only `SmsPort`; clash policy test. *(Lane B Task 4, `a1b9a366`;
      `OtpTest`.)*
- [x] Integration: sign-up creates account + household + member + device + own
      wrap in one transaction (failure leaves nothing). *(Lane B Task 3;
      trigger-forced rollback in `AccountRoutesTest`.)*

### W2.4 Admission — Tuesday/Wednesday

- [x] pending-session + resolve-by-secret + vouch + claim-with-wrap endpoints per
      §4; single-use, 5-min TTL, one vouch; QR string `hopa://pair?v=1&s=…&u=…`.
      *(Lane A Tasks 3–4, `40197f2f`, `aeb5e796`, `7616ddcb`.)*
- [x] Integration: unauthenticated session never becomes a device without vouch;
      double claim refused; wrap stored once; PC receives the wrapped key and the
      voucher's public key at claim. *(`AdmissionTest`, 13 tests.)*

### W2.5 Devices and admin API — Wednesday/Thursday

- [x] `GET /v1/devices`, `DELETE /v1/devices/{id}` (token family + wrap row, ≤ 5 s
      cut-off), `POST /v1/token/refresh` reuse detection already proven. *(Lane A
      Task 5, `6523d033`, `d436b8ca`, `46cdd1b8`; hard cut on reuse in `99416bd4`.)*
- [x] `GET /v1/admin/devices?household=` (live presence) — lane A Task 6, `92272bd2`,
      now read through `admin_list_devices` (`809c97e2`).
- [x] `POST /v1/admin/session` and `GET /v1/admin/households?query=` with the
      access log on every phone-number read. *(Lane B Task 5, `3fb56b2c`/`70fe854b`;
      `LookupTest`; V5 extends the member/household resolvers with `account_id`.)*
- [x] `diag.ping/state/reset` in the validator; server-authored tasks through
      `TaskRouter`; audit row names the admin. *(Lane A Task 6, `92272bd2`/`400126b1`.)*

### W2.6 Console — Wednesday/Thursday

- [x] Admin sign-in page, health page, household & device lookup page (member-id,
      household-id, phone, connected devices), diag sender button. *(Lane B Task 6,
      `e29fd206`/`85eb5e43`; `ConsoleTest`.)*
- [x] No conversation contents anywhere in the console (asserted by test).
      *(`ConsoleTest` sentinel scan of every route.)*

### W2.7 Clients — Thursday/Friday

- [x] Sidecar: pending session, QR render on **stderr** (image + `hopa://` string;
      stdout is the protocol channel), 5 s resolve-by-secret poll, claim, unwrap,
      credentials stored, gateway envelopes both directions. *(Lane A Tasks 7–8,
      `2e2914e2`, `93272f03`, `8d059231`, `60a1e3be`, `9c0176c0`; entry point
      `--pair-backend`/`backend.pair` in `e3c9be38`.)*
- [x] Android: sign-in screen, local-only mode when `backendUrl` is null, debug
      `buildConfigField` from `local.properties`, camera QR scanner with paste
      fallback, vouch sends the wrapped key. *(Lane B Tasks 7–8, `1b994122`,
      `d3f1cf07`, `2695a52d`, `7db884c5`; the manual camera run is still pending.)*

### W2.8 Evidence — Friday

- [ ] `docs/project/evidence/B2-accounts.md` records §8 with commands, trace ids
      and timestamps; `python tools/scripts/plan-status.py --plan
      docs/project/plan/backend` stays green.
- [x] `services/backend/README.md` rewritten to match the shipped Kotlin/Ktor
      service. *(Lane B Task 9, `8c4eec3b`; no Python sketch remains.)*

## 11. Lane B execution record (23 Nov 2026)

Lane B ran subagent-driven on `backend-week2-B`, stacked on `backend-week2-A`
(BASE `450f65b4`): one implementer per plan task, a fresh task reviewer per diff,
and one scoped re-review per fix round. Task 1 was already delivered by lane A and
was verified rather than re-run. Sync v0 stays outside this slice per the
[`week2b.md`](tasks/week2b.md) boundary; it becomes `tasks/week2-sync.md`.

### 11.1 Task status

| Plan task | Commit(s) | Review |
|---|---|---|
| 1. V2 migration | lane A `68bfb7d1`, V3/V4 | satisfied by lane A; verified |
| 2. E.164, argon2, admin seed | `c2d33f9a` | approved, no fix |
| 3. Sign-up and sign-in | `5d671046` | approved, no fix |
| 4. Optional SMS OTP | `a1b9a366` | approved, no fix |
| 5. Admin session and lookup | `3fb56b2c`, `70fe854b` | fix round 1 (tombstoned accounts, query kind) |
| 6. Console pages | `e29fd206`, `85eb5e43` | fix round 1 (console catch-all) |
| 7. Android sign-in and modes | `1b994122`, `d3f1cf07` | fix round 1 (429 message, pairing entry) |
| 8. Android scan and vouch | `2695a52d`, `7db884c5`, `7b3b5642` | fix round 1 (non-pending states, ktlint) |
| 9. README and evidence skeleton | `8c4eec3b` | approved, no fix |
| 10. Review follow-ups | `99f7e39f` | approved, no fix |

### 11.2 Decisions taken during execution

- The phone is normalized in one shared place: `Phones` moved to `core/backend`
  under the same package; sign-up, sign-in and OTP all use it.
- Sign-up's tombstoned-phone refusal is carried by the service (any row with the
  phone), while V2's partial unique index stays as the race backstop.
- OTP verify for an unknown phone creates a verified household-of-one with a
  throwaway password hash and no wrap; the flag stays false by default.
- Admin lookup by member/household needed the account id, so `V5__admin_account_id.sql`
  extends the two resolvers; `accounts` is then read by id in the admin path.
- Admin PII logging records `query_kind` and a truncated SHA-256 of the query, never
  the phone or uuid (exit check #9 forbids phones in logs).
- The console calls the same services as the JSON routes, is cookie-gated, and
  renders text nodes only; unknown `/console*` paths redirect unauthenticated callers.
- Android keeps two modes; an unreachable gateway degrades to local-only with a
  banner, and the gateway pairing card hides only when no gateway is configured.
- The scan confirm refuses non-`pending` sessions (the server reports
  expired/claimed/vouched as 200 with a `state`, not `E_SCHEMA`).
- Android acks a delivered `diag.*` on the link it arrived on; the sidecar refuses a
  second concurrent `backend.pair` with `ok=false, error="already pairing"`.

### 11.3 Review findings that changed the code

- Task 5: a tombstoned account still disclosed phone/verified on member and household
  lookups; the account read now filters `deleted_at` and falls back to status `none`.
- Task 6: unknown `/console/*` sub-paths answered 404 instead of the required 302 for
  unauthenticated callers; a trailing catch-all now handles them.
- Task 7: a 429 was shown as "gateway unreachable" and `hidePairingEntry` was never
  wired; 429 now has its own message and the card is threaded from the launch decision.
- Task 8: expired/claimed sessions still reached the confirm screen; the state is now
  checked first and the test uses the server's real 200-with-state response.

### 11.4 Deferred and parked

- Manual/deployed exit checks 1, 3, the manual part of 2 and 9 remain unproven; the
  evidence file's rows stay unticked.
- Parked minors for the final review: concurrent duplicate sign-up can still surface a
  500 instead of `E_AUTH`; Unicode digits pass `Phones`; admin email is
  case-sensitive; sign-in spans three transactions; `ScanScreen`/`AccountViewModel`
  use `remember`; the unreachable banner can overlap the top bar.
- Sync v0 is not in this slice (its mini-design and implementation remain week 3 work).

### 11.5 Verification at `99f7e39f`

| Command | Result |
|---|---|
| `apps\android\gradlew.bat -p services/backend :test --rerun-tasks` | 219 tests, 0 failures |
| `apps\android\gradlew.bat -p apps/android :backend:test --rerun-tasks` | 97 tests, 0 failures |
| `apps\android\gradlew.bat -p apps/android :app:testDebugUnitTest --rerun-tasks` | 89 tests, 0 failures |
| `apps\android\gradlew.bat -p apps/windows/sidecar test --rerun-tasks` | 44 tests, 0 failures |
| `py -3 tools/checks/check-links.py` | 0 broken links |
| `python tools/scripts/plan-status.py --plan docs/project/plan/backend` | green |

### 11.6 Exit-check status

| # | Status |
|---|---|
| 1 | pending: deployed round-trip with timestamps |
| 2 | automated half proven (`DeviceRevokeTest`, `TokenRotationTest`); manual timing pending |
| 3 | pending: real camera scan + encrypted task each way |
| 4 | proven (`AccountRoutesTest`) |
| 5 | proven (`OtpTest`) |
| 6 | proven (`LookupTest`, `ConsoleTest`) |
| 7 | automated half proven (`DiagSendTest`); device-level ack proven by `BackendSessionTest`; deployed arrival pending |
| 8 | proven (`TenantIsolationTest`, 1,000 attempts) |
| 9 | pending: grep of the deployed week's logs |
