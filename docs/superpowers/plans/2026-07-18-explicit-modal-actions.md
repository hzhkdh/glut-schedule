# Explicit Modal Actions Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Prevent update and notice dialogs from closing through outside taps or the system back button while preserving every explicit card action.

**Architecture:** Keep the existing `AlertDialog` components and business callbacks. Introduce one shared immutable `DialogProperties` value for the two target dialog families, use an empty `onDismissRequest`, and protect the contract with a focused source test.

**Tech Stack:** Kotlin, Jetpack Compose Material 3, JUnit 4, Gradle/JDK 17.

## Global Constraints

- Keep `versionCode = 117` and `versionName = "0.19.0"`.
- Do not alter unrelated dialogs.
- Do not publish or push the release.
- Users may close or navigate only through controls rendered inside the update or notice card.

---

### Task 1: Lock the modal dismissal contract

**Files:**
- Create: `app/src/test/java/com/glut/schedule/ExplicitModalActionsTest.kt`
- Modify: `app/src/main/java/com/glut/schedule/MainActivity.kt`

**Interfaces:**
- Consumes: existing `NoticePopupDialog` and `UpdateDialog` composables.
- Produces: private `EXPLICIT_ACTION_DIALOG_PROPERTIES: DialogProperties` used by all five target `AlertDialog` calls.

- [ ] **Step 1: Write the failing source contract test**

```kotlin
@Test
fun updateAndNoticeDialogsRequireExplicitCardActions() {
    val source = mainActivitySource()
    assertTrue(source.contains("private val EXPLICIT_ACTION_DIALOG_PROPERTIES = DialogProperties("))
    assertTrue(source.contains("dismissOnBackPress = false"))
    assertTrue(source.contains("dismissOnClickOutside = false"))
    assertEquals(5, Regex("properties = EXPLICIT_ACTION_DIALOG_PROPERTIES").findAll(source).count())
    assertEquals(5, Regex("""onDismissRequest = \{ \}""").findAll(source).count())
}
```

- [ ] **Step 2: Verify RED**

Run: `./gradlew.bat testDebugUnitTest --tests "com.glut.schedule.ExplicitModalActionsTest"`

Expected: FAIL because the shared properties and no-op dismiss callbacks do not exist.

- [ ] **Step 3: Add the minimal Compose configuration**

```kotlin
private val EXPLICIT_ACTION_DIALOG_PROPERTIES = DialogProperties(
    dismissOnBackPress = false,
    dismissOnClickOutside = false
)
```

For `NoticePopupDialog` and each of the four `UpdateDialog` states, use:

```kotlin
onDismissRequest = { },
properties = EXPLICIT_ACTION_DIALOG_PROPERTIES,
```

Keep all existing `TextButton` callbacks unchanged.

- [ ] **Step 4: Verify GREEN and regression suite**

Run: `./gradlew.bat testDebugUnitTest --tests "com.glut.schedule.ExplicitModalActionsTest"`

Expected: PASS.

Run: `./gradlew.bat testDebugUnitTest`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit the behavior change**

```powershell
git add app/src/main/java/com/glut/schedule/MainActivity.kt app/src/test/java/com/glut/schedule/ExplicitModalActionsTest.kt
git commit -m "限制更新与通知弹窗关闭方式"
```

### Task 2: Review and rebuild the unchanged-version release

**Files:**
- Review: all changes since `15f2a8b`.
- Output: `app/build/outputs/apk/release/glutShedule_0.19.0.apk`

**Interfaces:**
- Consumes: committed campus-image and modal behavior changes.
- Produces: signed local v0.19.0 APK plus verification evidence.

- [ ] **Step 1: Request independent code review**

Review `git diff 15f2a8b..HEAD` for correctness, security, Compose lifecycle, tests, and requirement coverage. Fix every Critical or Important finding with a failing regression test first.

- [ ] **Step 2: Re-run full verification after review fixes**

Run: `./gradlew.bat testDebugUnitTest`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Build release**

Run: `./gradlew.bat assembleRelease`

Expected: BUILD SUCCESSFUL and `glutShedule_0.19.0.apk` exists.

- [ ] **Step 4: Audit the APK**

Use Android build-tools `aapt dump badging` and `apksigner verify --verbose --print-certs`.

Expected: package `com.glut.schedule`, versionCode `117`, versionName `0.19.0`, valid V2 signature.

- [ ] **Step 5: Keep the local branch**

Confirm `git status --short --branch` is clean. Do not push, publish, merge, or delete the branch.
