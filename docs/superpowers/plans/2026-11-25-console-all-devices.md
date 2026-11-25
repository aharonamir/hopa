# Console: all devices and their status — implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** A `/console/phones` page listing every device across households, with live status and a masked phone that can be revealed per row.

**Architecture:** One SECURITY DEFINER function owned by the existing BYPASSRLS `hopa_admin` role (same pattern as V5), one service that reads it, one console route plus a rendered table. The reveal is a query parameter on the same page and writes `admin.pii.reveal`.

**Tech Stack:** Kotlin/Ktor, Postgres with RLS, kotlinx.html, JUnit via `PostgresHarness`.

**Spec:** `docs/superpowers/specs/2026-11-25-console-all-devices-design.md`

## Global Constraints

- The app role never bypasses RLS; only the `hopa_admin`-owned function reads across tenants.
- No page or log line renders or stores the full phone except the requesting response of a deliberate reveal; the audit line carries a 16-hex hash.
- Every console route requires the `hopa_admin` cookie.

## Review Focus

- A device whose member has no account still appears, with no phone column filled.
- A revoked device still appears, marked revoked, and is not called online.
- The 500-row cap holds.
- A reveal for an unknown device id renders the masked list and writes no audit line.
- The page cannot be reached without the admin cookie.

---

### Task 1: Migration and service

**Files:**
- Create: `services/backend/src/main/resources/db/admin/V6__admin_device_list.sql`
- Create: `services/backend/src/main/kotlin/com/hopa/backend/admin/AdminDeviceList.kt`
- Test: `services/backend/src/test/kotlin/com/hopa/backend/console/ConsoleTest.kt`

**Interfaces:**
- Produces: `AdminDeviceList.list(): List<AdminDeviceRow>` where
  `AdminDeviceRow(deviceId, householdId, platform, name, lastSeenAt, revoked, phone)`.

- [ ] **Step 1:** Write `the devices page lists phones and PCs across households` asserting two households' rows appear.
- [ ] **Step 2:** Run it; expect 404 (no route yet).
- [ ] **Step 3:** Add the migration and the service.
- [ ] **Step 4:** Commit.

### Task 2: Route and page

**Files:**
- Modify: `console/ConsoleRoutes.kt`, `console/ConsoleViews.kt`, `ServerConfig.kt`, `Main.kt`
- Test: `ConsoleTest.kt`

**Interfaces:**
- Consumes: `AdminDeviceList.list`, `ConsoleViews.phones(List<ConsolePhoneRow>)`.

- [ ] **Step 1:** Add `the devices page masks the phone until one row is revealed` (masked page has no full number; `?reveal=<deviceId>` shows it and writes one `admin.pii.reveal` without the number).
- [ ] **Step 2:** Run it; expect failure (404 / no reveal).
- [ ] **Step 3:** Add the route, the table and the `/console` link.
- [ ] **Step 4:** Run both tests; expect PASS.
- [ ] **Step 5:** Commit.

### Task 3: Live check

- [ ] **Step 1:** `:installDist`, restart the backend (Flyway applies V6), open `/console/phones`.
- [ ] **Step 2:** Reveal one row, confirm the full number and the `admin.pii.reveal` line.
- [ ] **Step 3:** Record it in `docs/project/evidence/B2-accounts.md` and commit.
