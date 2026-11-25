# Console: all devices and their status — design

Date: 2026-11-25
Status: approved by the user (all devices; masked phone per row with reveal).
Branch: `backend-tests`.

## Intent

The console can only reach devices through a household (`/console/lookup` →
`admin_list_devices(household)`). There is no way to answer "what is connected
right now, and is anything stuck offline?" across the whole deployment.

## Goals

- One page, `/console/phones`, listing every device across every household:
  name, platform, online/offline/revoked, last seen, household (link to the
  household page), and the owning account's phone masked to `…1234`.
- A per-row `reveal` link that renders that one full number and writes an
  `admin.pii.reveal` line (hashed, never the number).
- Reachable from `/console` with one link.

## Non-goals

- Live updates (the page is a snapshot; reload to refresh).
- Sorting, filtering or paging beyond a hard 500-row cap.
- Editing or revoking from this page (the device page already exists).

## Approaches

(a) Read `devices` with `db.read` — impossible: the app role is NOSUPERUSER
NOBYPASSRLS and the policy denies rows without a tenant GUC; weakening that is
out of the question. (b) Loop households from a list endpoint — there is no
household list, and it would be N queries. (c) A SECURITY DEFINER function owned
by the existing `hopa_admin` BYPASSRLS role, exactly like `admin_list_devices`
in V5. Picked (c): it follows the established provisioning pattern (V5), keeps
the app role policy-bound, and is one query.

## Design

- `db/admin/V6__admin_device_list.sql`: `admin_list_all_devices()` returns
  `device_id, household_id, platform, name, last_seen_at, revoked_at, phone`
  (phone from the member's live account), ordered by last seen, `limit 500`;
  owned by `hopa_admin`, `execute` granted to the deploy role and `hopa_app`.
- `AdminDeviceList` (`admin/`) runs the function in `db.read` and returns rows.
- `GET /console/phones`: admin cookie required; the list renders masked; when
  `?reveal=<deviceId>` names a row, that row renders the full number and the
  route writes `admin.pii.reveal` with `query_hash` (16 hex of the phone).
- `ConsoleViews.phones(...)` renders the table; `/console` gains an
  "All devices" link.
- Wiring: `ServerConfig.deviceList`, built in `Main` and in the console test.

## What is left to the builder

- Column order and the exact reveal link text.
- Whether a page load should also write a non-PII `admin.devices.read` line
  (not planned; the reveal is the audited read).
