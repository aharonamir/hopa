# Sign-in session start + console account removal — design

Date: 2026-11-25
Status: approved by the user (erase semantics chosen).
Branch: `backend-tests`.

## Intent

Two gaps found while running week 2 end to end on a real phone and a real PC:

1. **A fresh sign-up is invisible until the app is relaunched.** Sign-up writes
   the identity, but the gateway socket only opens on the next `onForeground`,
   so the console shows the device `offline` (no `last_seen_at`) right after
   registering. People read that as "the phone is not in the backend".
2. **There is no way to remove an account from the console.** A test account
   (and its household, devices and tokens) can only be removed with SQL.

## Goals

- The gateway socket opens as soon as sign-up/sign-in stores the identity, with
  no relaunch; the console then shows the device online.
- The console can erase an account: its row, its household and everything the
  household owns, with the devices cut off and an audit line.
- The erased phone number can register again immediately (erase, not tombstone).

## Non-goals

- Self-service deletion from the phone app (week 4 territory).
- A retention window, export, or a "deleted accounts" list.
- Removing an account that owns more than one household (today every account
  owns exactly one; the console removes that one and says so).

## Approaches

**Session start.** (a) Poll settings in `BackendSession` and connect when
`backendDeviceId` first becomes non-blank; (b) call a new
`BackendSession.identityChanged()` from `SignInRoute` once `signedIn` flips,
reusing the `disconnect(); if (foreground) connect()` sequence `pair()` already
uses; (c) force a process restart. Picked (b): it is the existing seam, it needs
no new observer, and it cannot fire twice for one sign-in. (a) would also fire
on a token refresh writing the same id.

**Removal semantics.** (a) Erase the account row and its household; (b) tombstone
`accounts.deleted_at`; (c) tombstone plus a separate purge. Picked (a) on the
user's instruction: this is a development console, and the number must be
reusable for testing. `phoneExists` deliberately counts tombstones, so (b) would
burn the number forever.

## Design

### Session start

- `BackendSession.identityChanged()`: `disconnect()`, then `connect()` when
  `foreground` is true, mirroring `pair()`.
- `SignInRoute`: on `state.signedIn`, call it before `onSignedIn()`.
- No token or URL is logged.

### Console removal

- New `GET /console` row action: a `<form method="post"
  action="/console/account/remove">` on each lookup row carrying `household` and
  `member`, a text input named `confirm`, and a submit button. The row shows the
  masked phone tail (`…5165`) and the page asks for that tail.
- `POST /console/account/remove`:
  - admin cookie required (same gate as every other console route);
  - reject a cross-site `Origin` (reuses `ApplicationCall.crossSite()`);
  - resolve the member's household through `HouseholdLookup`;
  - refuse `accountStatus = "none"` (nothing to erase);
  - `confirm` must equal the last four digits of the phone;
  - revoke every device of the household (`PgDevices.revokeDevice`, which cuts
    the token families and the wrap) and `DeviceRegistry.kick` each live socket;
  - `delete from households` in the tenant transaction (cascades members,
    devices, tokens, tasks, wraps, pending pairings, pairing sessions);
  - `delete from accounts` in a global read/transaction (no policy);
  - write `admin.account.remove` with `admin_id` and `household_id` (no phone,
    no tokens);
  - render a result page with the counts removed.
- Errors render the existing `ConsoleViews.error` page with 400/404/503.

## What is left to the builder

- Exact copy of the confirm label, and whether the row action is a button or a
  link to a small confirm page. A single form with a typed tail is the default.
- Whether to also show "Remove account" on the device page (not planned).
- Test placement: a new `AccountRemovalTest` beside `ConsoleTest`, plus a
  `BackendSessionTest` case for `identityChanged()`.
