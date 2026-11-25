# Sign-in socket start + console account removal — implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A sign-up opens the gateway socket immediately, and the console can erase an account and its household.

**Architecture:** One method on the app's existing `BackendSession` (reusing the `disconnect`/`connect` seam `pair()` already has), called from `SignInRoute`. One backend service that revokes each device, deletes the household (every tenant table cascades) and deletes the account row, behind a console POST with the same admin cookie and same-site check as sign-in.

**Tech Stack:** Kotlin/Ktor backend on Postgres (RLS, tenant GUC), Compose Android app, Kotlinx HTML console, JUnit via the existing `PostgresHarness`.

**Spec:** `docs/superpowers/specs/2026-11-25-signin-session-and-account-removal-design.md`

## Global Constraints

- Every tenant read/write goes through `db.tenant(householdId)`; `accounts` is a global table read through `db.read`.
- No phone number, token or key may be logged or rendered; the console shows only `…` + last four digits.
- Console mutations are `POST` with the `hopa_admin` cookie and `call.crossSite()` refused.
- Removal is an erase: the account row and the household go; the phone may sign up again.
- Tests: `apps\android\gradlew.bat -p services/backend :test --tests "*<Class>*"` and `apps\android\gradlew.bat :app:testDebugUnitTest --tests "*<Class>*"` (run from `apps\android` for the second).

## Review Focus

- Two foreground events in a row, or a token refresh, must not leave two sockets open.
- Removing a household with a live device must close its socket (`DeviceRegistry.kick`) and leave no row behind.
- A wrong `confirm` tail, or a member with `accountStatus = "none"`, deletes nothing.
- A cross-site POST is refused with 403 and deletes nothing.
- The erased phone registers again immediately, and the erased device's tokens stop working.

---

### Task 1: `BackendSession.identityChanged()`

**Files:**
- Modify: `apps/android/app/src/main/kotlin/com/hopa/app/link/session/BackendSession.kt` (after `pair`, ~line 127)
- Test: `apps/android/app/src/test/kotlin/com/hopa/app/link/session/BackendSessionTest.kt`

**Interfaces:**
- Produces: `suspend fun BackendSession.identityChanged()` — no parameters, no return.

- [ ] **Step 1: Write the failing test**

Add to `BackendSessionTest`, using the existing `SessionFixtures.session(...)` and a counting `linkFactory`:

```kotlin
@Test
fun `identityChanged opens the socket when the app is in front`() = runBlocking {
    val settings = FakeSettings().apply { /* seed a signed-in identity as the other tests do */ }
    val links = AtomicInteger()
    val session = session(settings, linkFactory = { _, _, _, _, _ ->
        links.incrementAndGet()
        FakeLink(/* as the other tests build it */)
    })
    session.onForeground()
    session.identityChanged()
    assertEquals(2, links.get())
    session.disconnect()
}
```

- [ ] **Step 2: Run it and expect a compile failure**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "*BackendSessionTest*" --no-daemon` (from `apps\android`)
Expected: FAIL — `identityChanged` unresolved.

- [ ] **Step 3: Implement**

```kotlin
/** The stored identity just changed (sign-up/sign-in): open it now, not next launch. */
suspend fun identityChanged() {
    disconnect()
    if (foreground) {
        connect()
    }
}
```

- [ ] **Step 4: Run the test**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "*BackendSessionTest*" --no-daemon`
Expected: PASS, including the existing refresh and diagnostic tests.

- [ ] **Step 5: Add the double-call case**

`calling it twice keeps one live link`: call `identityChanged()` twice while foreground and assert `currentLink()` is the second link and the first one's `stopped` flag is true (the `FakeLink` fixture already records `stopped`).

- [ ] **Step 6: Commit**

```bash
git add apps/android/app/src/main/kotlin/com/hopa/app/link/session/BackendSession.kt apps/android/app/src/test/kotlin/com/hopa/app/link/session/BackendSessionTest.kt
git commit -m "feat(android): open the gateway socket as soon as sign-in stores the identity"
```

### Task 2: Call it from the sign-in screen

**Files:**
- Modify: `apps/android/app/src/main/kotlin/com/hopa/app/account/SignInRoute.kt:32-36`

**Interfaces:**
- Consumes: `AppContainer.backend` (`BackendSession`) and `identityChanged()` from Task 1.

- [ ] **Step 1: Wire it**

```kotlin
LaunchedEffect(state.signedIn) {
    if (state.signedIn) {
        container.backend.identityChanged()
        onSignedIn()
    }
}
```

- [ ] **Step 2: Build**

Run: `.\gradlew.bat :app:assembleDebug --no-daemon` (from `apps\android`)
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add apps/android/app/src/main/kotlin/com/hopa/app/account/SignInRoute.kt
git commit -m "feat(android): start the gateway link right after sign-up or sign-in"
```

### Task 3: `AccountRemoval` service

**Files:**
- Create: `services/backend/src/main/kotlin/com/hopa/backend/admin/AccountRemoval.kt`
- Test: `services/backend/src/test/kotlin/com/hopa/backend/console/AccountRemovalTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  class AccountRemoval(
      private val db: Database,
      private val inventory: DeviceInventory,
      private val kick: (String) -> Unit,
      private val log: EventLog,
  ) {
      sealed interface Outcome {
          data class Removed(val householdId: String, val devices: Int) : Outcome
          data class Refused(val code: String, val message: String) : Outcome
      }
      fun remove(adminId: String, householdId: String, memberId: String): Outcome
  }
  ```

- [ ] **Step 1: Write the failing test**

In `AccountRemovalTest` (reuse `ConsoleTest`'s fixture helpers: `seedAccountHousehold`, `seedAdminSession`, `config(registry)`):

```kotlin
@Test
fun `removing an account erases its household, devices and tokens`() {
    val fixture = seedAccountHousehold()
    val removal = AccountRemoval(db, PgDevices(db), registry::kick, log)
    val outcome = removal.remove(adminId, fixture.householdId, fixture.memberId)
    assertTrue(outcome is AccountRemoval.Outcome.Removed)
    assertEquals(1, (outcome as AccountRemoval.Outcome.Removed).devices)
    assertEquals(0, rowCount("households", fixture.householdId))
    assertEquals(0, rowCount("accounts", fixture.accountId))
}
```

- [ ] **Step 2: Run it and expect a compile failure**

Run: `apps\android\gradlew.bat -p services\backend :test --tests "*AccountRemovalTest*" --no-daemon`
Expected: FAIL — `AccountRemoval` unresolved.

- [ ] **Step 3: Implement `remove`**

Order: resolve `account_id` from `members` in `db.tenant(householdId)` (null → `Refused("E_NOT_FOUND", "no account behind this member")`); list `devices.id` in the same tenant; for each call `inventory.revokeDevice(id, householdId)` and `kick(id)`; `db.tenant(householdId) { delete from households where id = ?::uuid }`; `db.read { delete from accounts where id = ?::uuid }`; `log.event("admin.account.remove", mapOf("admin_id" to adminId, "household_id" to householdId))`; return `Removed(householdId, devices.size)`.

- [ ] **Step 4: Run the test**

Expected: PASS.

- [ ] **Step 5: Add the two refusal tests and run them**

`a member with no account is refused and nothing is deleted` (insert a household/member with `account_id = null`, expect `Refused` and the household still present) and `a removal closes the live socket` (put a device in the registry, remove, assert `registry.online(id) == false`).

- [ ] **Step 6: Commit**

```bash
git add services/backend/src/main/kotlin/com/hopa/backend/admin/AccountRemoval.kt services/backend/src/test/kotlin/com/hopa/backend/console/AccountRemovalTest.kt
git commit -m "feat(backend): add an account eraser for the console"
```

### Task 4: Console route and page

**Files:**
- Modify: `services/backend/src/main/kotlin/com/hopa/backend/console/ConsoleRoutes.kt` (new `post` beside `/console/device/{id}/diag`)
- Modify: `services/backend/src/main/kotlin/com/hopa/backend/console/ConsoleViews.kt` (`renderLookupRow`, plus a `removed` page)
- Test: `services/backend/src/test/kotlin/com/hopa/backend/console/AccountRemovalTest.kt`

**Interfaces:**
- Consumes: `AccountRemoval.remove` from Task 3; `ConsoleLookupRow.maskedPhone`.
- Produces: `POST /console/account/remove` with form fields `household`, `member`, `confirm`.

- [ ] **Step 1: Write the failing form test**

```kotlin
@Test
fun `the console removes an account when the tail matches`() = testApplication {
    application { module(config()) }
    val admin = seedAdminSession()
    val fixture = seedAccountHousehold()          // phone ends 4567
    val response = client.post("/console/account/remove") {
        header("Cookie", "hopa_admin=${admin.token}")
        contentType(ContentType.Application.FormUrlEncoded)
        setBody("household=${fixture.householdId}&member=${fixture.memberId}&confirm=4567")
    }
    assertEquals(HttpStatusCode.OK, response.status)
    assertTrue(response.bodyAsText().contains("Account removed"))
    assertEquals(0, rowCount("households", fixture.householdId))
}
```

- [ ] **Step 2: Run it and expect 404/405**

Expected: FAIL — the route does not exist.

- [ ] **Step 3: Add the route**

Gate on `consoleAdminOrRedirect`, refuse `call.crossSite()` with 403, read the bounded form, require `household` and `member` as uuids (`ConsoleViews.error(400, …)`), resolve the row through `config.householdLookup.lookup(admin.adminId, household)?.firstOrNull { it.memberId == member }`, refuse `accountStatus == "none"` (400), require `confirm.trim() == row.phone?.takeLast(4)` (400, "type the last four digits"), then call `AccountRemoval(db, config.devices as? DeviceInventory ?: return 503, config.registry::kick, config.log).remove(...)` and render `ConsoleViews.removed(...)` or `ConsoleViews.error`.

- [ ] **Step 4: Add the page form and result view**

In `renderLookupRow`, when `maskedPhone != null`, render a form posting to `/console/account/remove` with hidden `household`, hidden `member`, a text input named `confirm` labelled `Type ${maskedPhone} to remove this account`, and a submit button.

- [ ] **Step 5: Add the abuse tests**

`a wrong tail deletes nothing` (expect 400 and the household still present), `a cross-site post is refused` (send `Origin: https://evil.test`, expect 403 and nothing deleted), and `the phone can register again after the erase` (sign up through `POST /v1/account/sign-up` with the erased phone, expect 201).

- [ ] **Step 6: Run the class**

Run: `apps\android\gradlew.bat -p services\backend :test --tests "*AccountRemovalTest*" --tests "*ConsoleTest*" --no-daemon`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add services/backend/src/main/kotlin/com/hopa/backend/console/ConsoleRoutes.kt services/backend/src/main/kotlin/com/hopa/backend/console/ConsoleViews.kt services/backend/src/test/kotlin/com/hopa/backend/console/AccountRemovalTest.kt
git commit -m "feat(console): add a remove-account action with a typed confirmation"
```

### Task 5: Prove it on the running stack

**Files:**
- Modify: `docs/project/evidence/B2-accounts.md`

- [ ] **Step 1: Rebuild and restart** the backend (`:installDist`, plain `lib\*`), then `adb … :app:assembleDebug` and install the APK.

- [ ] **Step 2: Register on the phone** with a fresh number and, without relaunching, confirm the console shows the device `online` (this is the fix for the reported gap).

- [ ] **Step 3: Erase that account from the console**, confirm the phone's socket is cut and the household page is gone, then register the same number again.

- [ ] **Step 4: Record** the timestamps, ids and the two commands in `docs/project/evidence/B2-accounts.md`, then commit:
  `docs(accounts): record the immediate connect and the console erase`
