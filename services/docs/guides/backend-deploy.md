# Backend deploy: Render, Frankfurt

`render.yaml` at the repository root declares two web services and one database:

| Resource | Name | Runtime | Region | Plan |
|---|---|---|---|---|
| Gateway | `hopa-backend` | Docker | `frankfurt` | `free` for v0 |
| Database | `hopa-backend-db` | Postgres 16 | `frankfurt` | `free` |
| Relay (existing) | `hopa-relay` | Python | `singapore` | `free` |

The Blueprint is the deploy. There is no deploy workflow and no `flyctl`: a push
to the connected branch rebuilds the image and Render rolls it out.

## Secrets (dashboard only, never the repository)

| Key | What it is |
|---|---|
| `HOPA_BOOTSTRAP_TOKEN` | Operator token for `POST /v1/pair/sessions`; 32+ random bytes |
| `HOPA_FCM_PROJECT` | Firebase project id |
| `HOPA_FCM_CREDENTIALS` | The service-account JSON, one line |

`DATABASE_URL` comes from the Blueprint (`fromDatabase`), so it is never copied by
hand.

## First deploy

1. In Render, create a Blueprint from the repository and accept `render.yaml`.
2. Fill the three secrets above in the dashboard.
3. Let the service build. Migrations run on boot: `Main` calls Flyway **before**
   the listener opens, so a failed migration fails the health check instead of
   serving half a schema.
4. Open `https://hopa-backend.onrender.com/v1/health` and expect
   `{"ok":true,"version":"0.1.0","db":"ok"}`.

## Verify the deploy role

RLS is `FORCE`d, but a superuser would still bypass it. Run this once against the
deployed database (Render dashboard psql):

```sql
select rolsuper from pg_roles where rolname = current_user;
```

It must return `false`. If it returns `true`, the Render user owns the cluster and
the tenant policies are not protecting anything; create a non-superuser role
before the beta.

## Rate limits and the edge

The per-IP rate limits on unauthenticated routes key on the **first
`X-Forwarded-For` hop** (`remoteAddress` in the gateway). That is only
meaningful if the edge overwrites a client-supplied `X-Forwarded-For`; confirm
this at the Render edge before the demo, or record the trusted proxy hop and
change the limiter key to match.

## Provision `hopa_admin` (required before the first V2 migration)

Admin lookups read tenant rows through four `SECURITY DEFINER` functions owned by
`hopa_admin`, a non-login role with `BYPASSRLS`. `FORCE ROW LEVEL SECURITY` binds
the table owner too, so the functions cannot be owned by the normal migration or
runtime role. Provision the role once as a **superuser** (creating a
`BYPASSRLS` role needs more than `CREATEROLE` in PostgreSQL 16):

```sql
create role hopa_admin nologin bypassrls;
```

The migration also requires the role running Flyway to be able to alter function
owners. Either:

- run the migration as a database **superuser**; or
- grant the migration role `hopa_admin` **with admin option, set false** for the
  duration of the migration, and revoke it afterwards.

The latter path is only temporary. Before the migration's final guard, the
deploy role must have no residual `ADMIN OPTION` on `hopa_admin`; otherwise V2
fails loudly with the operator line to run. After the migration, verify both
roles:

```sql
-- hopa_admin must be nologin + bypassrls; the runtime role must not be superuser.
select rolname, rolsuper, rolbypassrls, rolcanlogin
from pg_roles
where rolname in ('hopa_admin', current_user);

-- Neither the runtime role nor the migration role may be able to SET ROLE into
-- the BYPASSRLS owner, or re-grant it.
select r.rolname, m.roleid::regrole, m.admin_option
from pg_auth_members m
join pg_roles r on r.oid = m.member
where m.roleid = 'hopa_admin'::regrole;

-- If a non-superuser row remains, remove it as a superuser:
revoke hopa_admin from <deploy_role>;
revoke admin option for hopa_admin from <deploy_role>;
```

The runtime login (`hopa_app` in `render.yaml`) must not be a superuser and must
not hold `hopa_admin` membership: a SQL injection that can `SET ROLE` into the
BYPASSRLS owner defeats RLS.

## Upgrading to V5 (existing database)

`V5__admin_account_id.sql` drops and recreates the two `hopa_admin`-owned
resolvers `admin_resolve_member` and `admin_resolve_household` (adding
`account_id`), so it needs the **same temporary provisioning as V2**. This is
required on **every existing database** that reaches V5, not only the first V2
run: on a V2-V4 database with a non-superuser deploy role, V5 fails until the
provisioning is re-applied.

Re-run one of the two paths above:

- run the migration as a database **superuser**; or
- grant the migration role `hopa_admin` **with admin option** for the duration
  of the migration, then revoke it and re-check the two residual queries in
  the previous section (`rolsuper`/`rolbypassrls` and the `pg_auth_members`
  admin-option scan).

## Free tier

A free web service sleeps after roughly 15 minutes without traffic, dropping every
WebSocket. The gateway's 25 s heartbeat keeps it awake while a device is connected,
but the exit run must warm it first:

```powershell
curl https://hopa-backend.onrender.com/v1/health
```

The free Postgres instance is also time-limited. Move both to paid plans before
beta; the evidence file records this as a release gate.

## Check and repair from the terminal

`tools/scripts/render-check.ps1` reads the same facts the dashboard shows:
health, the live deploy and its commit, the service state, `DATABASE_URL`, and
the Postgres instance. It wakes a sleeping service, waits for a deploy, and can
redeploy `main` when the live commit is behind.

```powershell
# Health, live commit, database and free-tier expiry. Wakes the service if needed.
.\tools\scripts\render-check.ps1

# Wait longer than the default 150 s for a cold start.
.\tools\scripts\render-check.ps1 -WakeTimeoutSec 300

# Deploy the current branch HEAD only when the live commit differs from local.
.\tools\scripts\render-check.ps1 -RedeployIfStale

# Deploy now, optionally clearing the build cache.
.\tools\scripts\render-check.ps1 -Redeploy -ClearCache

# A specific commit, then wait for it to go live.
.\tools\scripts\render-check.ps1 -Redeploy -Commit <sha>

# Show the deploy request without sending it.
.\tools\scripts\render-check.ps1 -RedeployIfStale -DryRun

# No Render API: health only, for a machine without the key.
.\tools\scripts\render-check.ps1 -NoApi -Url https://hopa-backend-test.onrender.com
```

The Render API half needs `RENDER_API_KEY` in the environment; the script never
prints it. Exit code `0` means the service is healthy and the database answers,
`1` means a check failed, and `2` means the service or key could not be resolved.
`-Json` emits one machine-readable object with the same fields.

The database line shows `expires in N day(s)` when the free instance is close to
its expiry date. Treat that as a release gate, not a warning to ignore.

## Smoke the image locally

```powershell
docker build -f services/backend/Dockerfile -t hopa-backend .
docker run -d --rm -p 18083:8080 -e PORT=8080 hopa-backend
curl http://127.0.0.1:18083/v1/health
docker stop (docker ps -q --filter ancestor=hopa-backend)
```

Without `DATABASE_URL` the service boots with `"db":"unconfigured"`; that is the
expected smoke result.

## Exit run (20 round-trips)

After warming the service and pairing two devices:

```powershell
apps\android\gradlew.bat -p services/backend exitRun --args="--url wss://hopa-backend.onrender.com/v1/gateway --token-a <a> --token-b <b> --rounds 20"
```

Each line prints the trace id. Paste the twenty ids into
`docs/project/evidence/B1-gateway.md`.

## Rollback

Render keeps the previous deploy: use **Rollback** on the service. Migrations are
forward-only and v0 only adds tables, so the previous image still runs against the
new schema. The relay is untouched by a backend rollback.
