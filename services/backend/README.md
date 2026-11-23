# HOPA backend

The Kotlin/Ktor service that gives HOPA real users. It is the gateway and HTTP
API: a phone signs up with an E.164 number + password and becomes a household of
one, a Windows PC joins by a QR the phone scans and vouches for, the two devices
exchange end-to-end-encrypted tasks over a WebSocket gateway, and the team has a
server-rendered admin console that looks up households and fires diagnostic
tasks.

## What it is

| Concern | What ships |
|---|---|
| Gateway | `wss://…/v1/gateway` — devices exchange `task.create`/`task.ack` frames with encrypted bodies |
| HTTP API | `/v1/*` — pairing, accounts, OTP, devices, admin session/lookup, traces (see `spec/openapi/backend.v1.yaml`) |
| Pairing / admission | phone sign-up, `POST /v1/pair/pending` + resolve-by-secret + vouch + claim, one-time QR |
| Accounts | `accounts` table (phone unique, argon2id), sign-up → household-of-one in one transaction, uniform `E_AUTH` |
| Admin console | server-rendered pages under `/console` (sign-in, health, household & device lookup, diag sender), never conversation contents |
| Diag tasks | `diag.ping` / `diag.state` / `diag.reset` — the only plaintext bodies, admin-only, audit-logged |
| Health | `GET /v1/health` → `{ok, version, db, queueDepth?}`; `GET /v1/version` → `{version, protocol}` |

## What it explicitly does not store

- **Content keys.** The server never sees the household content key. It stores
  only `household_key_wraps` rows — the key wrapped to each device's X25519
  public key (X25519 ECDH → HKDF-SHA256 → AES-256-GCM). The server is
  content-blind by construction.
- **Plaintext task bodies.** Every task body is encrypted (`{"sealed": …}`).
  The only plaintext bodies are the three admin-authored `diag.*` envelopes,
  and those carry no user content.
- **Phone numbers on the wire or in tokens.** The E.164 phone is a login lookup
  only — never a key, never returned in a response, never written to a token or
  a log line (admin reads are logged as a hashed `admin.pii.read` event).

## Running tests

```powershell
# Whole backend suite (Docker Desktop must be running; Testcontainers)
apps\android\gradlew.bat -p services/backend :test --rerun-tasks
```

Gradle is always `apps\android\gradlew.bat`, run from the repository root.

## Migrations

Flyway migrations run at boot, before the listener opens: `Main` calls
`Database.migrate()` and only then starts Netty. A container that cannot migrate
fails the health check instead of serving half a schema.

| Migration | Adds |
|---|---|
| `V1__init.sql` | households, members, devices, pairing_sessions, refresh/access tokens, tasks, task_events, idempotency_keys, trace_spans; RLS `FORCE` on all tenant tables |
| `V2__accounts.sql` | accounts, household_key_wraps, admins, admin_sessions, pending_pairings; `members.account_id`; the four `hopa_admin`-owned `SECURITY DEFINER` functions |
| `V3__pending_wrap.sql` | `pending_pairings.wrapped_key` |
| `V4__voucher_key.sql` | `pending_pairings.voucher_device_id` |
| `V5__admin_account_id.sql` | `account_id` on `admin_resolve_member` / `admin_resolve_household` |

### `hopa_admin` provisioning (required before the first V2 migration)

Admin lookups read tenant rows through four `SECURITY DEFINER` functions owned
by `hopa_admin`, a non-login role with `BYPASSRLS`. `FORCE ROW LEVEL SECURITY`
binds the table owner too, so these functions cannot be owned by the normal
runtime role. The role must be provisioned once before the first V2 migration,
and the migration must be run by a **superuser** (the Render database owner): a
non-superuser deploy role cannot both hold the `hopa_admin` membership needed to
alter the function owners and pass the end-of-migration residual guard. The
exact SQL and the post-migration checks are in
[`services/docs/guides/backend-deploy.md`](../docs/guides/backend-deploy.md).

## Environment variables

| Variable | Default | Purpose |
|---|---|---|
| `PORT` | `8080` | HTTP listen port (Netty) |
| `HOPA_VERSION` | `0.1.0` | Version reported by `/v1/health` and `/v1/version` |
| `DATABASE_URL` | — | Postgres URL, `postgres://user:pass@host:port/db` (Render supplies it); absent → `db:"unconfigured"` |
| `JDBC_DATABASE_URL` | — | Escape hatch for an already-JDBC URL; takes precedence over `DATABASE_URL` and pairs with `DATABASE_USER`/`DATABASE_PASSWORD` |
| `DATABASE_USER` | *(empty)* | Username used with `JDBC_DATABASE_URL` |
| `DATABASE_PASSWORD` | *(empty)* | Password used with `JDBC_DATABASE_URL` |
| `HOPA_BOOTSTRAP_TOKEN` | — | Operator token for the week-1 pairing path (secret) |
| `HOPA_FCM_PROJECT` | — | Firebase project id; both it and `HOPA_FCM_CREDENTIALS` are required to enable FCM push |
| `HOPA_FCM_CREDENTIALS` | — | Firebase service-account JSON, one line (secret) |
| `HOPA_PUBLIC_HOST` | `hopa-backend.onrender.com` | Host rendered into pairing QR `u=` |
| `HOPA_ADMIN_EMAIL` | — | Bootstrap admin email; seeds `admins` at boot (no-op unless both it and the password are set) |
| `HOPA_ADMIN_PASSWORD` | — | Bootstrap admin password; seeds `admins` at boot (hashed, never logged) |
| `HOPA_SMS_ENABLED` | `false` | Enables the SMS OTP routes; when false they answer `E_UNSUPPORTED` |

## Module map

Package `com.hopa.backend`:

| Module | Owns |
|---|---|
| `accounts` | sign-up / sign-in, E.164 + argon2, the admin seed, `SmsPort` / `LogSmsPort`, OTP state machine and routes |
| `admin` | admin sessions, household lookup (definer-function reads + `admin.pii.read` access log), diag sender |
| `console` | server-rendered `/console` pages (kotlinx.html) |
| `pairing` | pending sessions, vouch, claim, token issuance and rotation, device list/revoke |
| `gateway` | the WebSocket gateway and the live `DeviceRegistry` |
| `tasks` | task store/router and the server-authored `diag.*` path |
| `push` | FCM sender and the guarded push port |
| `trace` | trace span store, recorder and query routes |
| `store` | the `Database` pool, Flyway, probes, device and task persistence |
| `http` | health/version routes, rate limiter, request bodies, error envelope |
| `protocol` | envelope types, validator and the gateway codec |

Two small support packages complete the picture: `auth` (token lookup and the
revoke invalidator) and `log` (the JSON event log that `admin.pii.read` writes
into).

## Runbook notes

- **Process-local state, one container.** Rate limits (per IP and per phone) and
  the OTP code map live in this process's memory. v0 runs a single container; a
  second container would not share these. OTP codes are single-use, 6 digits,
  5-minute TTL, 3 attempts per code, 3 codes per phone per hour.
- **`LogSmsPort` is dev-only.** It logs the OTP code at INFO — and only ever when
  `HOPA_SMS_ENABLED=true`. It must never back a real vendor; it exists so a local
  phone can finish the OTP loop without a carrier. It never logs the phone number.
- **Admin PII reads are logged, not the phone.** Every admin lookup that resolves
  a phone number writes one `admin.pii.read` event carrying `admin_id`,
  `query_kind` and a 16-hex `query_hash` — never the raw phone or uuid.
- **Phone numbers stay out of logs, tokens and the wire.** The E.164 number is a
  login lookup only and is never returned, never written to a token, and never
  logged.

## Deploy

The container builds on `services/backend/Dockerfile` (distroless Java 21,
`ENTRYPOINT com.hopa.backend.MainKt`) and migrates on boot. Deployment,
secret provisioning, the `hopa_admin` role setup and the free-tier notes live in
[`services/docs/guides/backend-deploy.md`](../docs/guides/backend-deploy.md);
`render.yaml` at the repository root is the Blueprint.
