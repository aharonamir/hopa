# OpenAPI contracts

One file per service, versioned in git, reviewed like code.

| File | Service | Status |
|---|---|---|
| `bridge.v1.yaml` | `services/bridge` (and the pool) | current surface, no `/v1` prefix yet |
| `backend.v1.yaml` | `services/backend` (planned) | protocol v0 frozen; gateway frames live in `contracts/schemas/gateway.v1.schema.json` |

## Rules

1. **Same pull request.** A path, method, request or response change edits the
   spec in the same PR that changes the handler. There is no follow-up ticket.
2. **Both directions are checked.** `services/tools/checks/check-openapi.py` reads the
   `self.path == ...` chain in `bridge.py` / `bridge_pool.py` and fails when a
   handler is missing from the spec *or* a spec path has no handler.
3. **Prefix routes are declared, not hidden.** A route handled by
   `self.path.startswith(...)` (e.g. `/images/`) matches spec paths with the same
   prefix. A routing prefix such as `/d/{serial}` is listed in `x-path-prefixes`.
4. **Auth is part of the contract.** The bootstrap token, the paired token and
   the twelve-hour lifetime are documented per endpoint, not inferred by clients.
5. **Versioning.** The file name carries the major version. The `/v1` path prefix
   is deliberately deferred until a client ships independently of the repository;
   see `docs/reference/protocols.md`.

## Adding an endpoint

1. Add the handler.
2. Add the path (and its schemas) to the spec.
3. Run `python services/tools/checks/check-openapi.py` — it fails both ways on purpose.
4. If the endpoint needs the pooled routing, document the header it uses.

## The admin console (`/console/*`)

The console is server-rendered HTML (kotlinx.html), not JSON, so it lives outside
the YAML contract files. Its session is the opaque admin token in the `hopa_admin`
cookie (`HttpOnly`, `Secure`, `Path=/`, `SameSite=Strict`); the token never appears
in a URL, a log or a rendered page.

| Path | Method | Auth | Purpose |
|---|---|---|---|
| `/console/sign-in` | GET | none | the sign-in form |
| `/console/sign-in` | POST | none | sign in; 303 to `/console` on success, uniform 401 re-render otherwise |
| `/console` | GET | cookie | version + database status |
| `/console/lookup?query=` | GET | cookie | household lookup (member id, household id or E.164 phone) with masked phone and device presence |
| `/console/device/{id}?household=` | GET | cookie | one device's state and the diagnostic form |
| `/console/device/{id}/diag` | POST | cookie | send a diagnostic; 202 with a task-id/trace/state confirmation |

Any unauthenticated `/console*` request except the sign-in form redirects (302) to
`/console/sign-in`. Console pages never read or render a task body, task event,
turn or chat.
