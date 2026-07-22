# Task 3 Report: Semester probing, validated caching, and explicit viewing

## Outcome

- Catalog planning keeps admission-autumn through the portal-selected semester as metadata and exposes at most one immediate next-semester probe candidate.
- Guilin/Nanning autumn term values use `2`/`3`; autumn-to-spring uses the next real year option.
- Login performs one optional next-semester `importSemester` call. Only a structurally valid, non-empty payload is promoted and cached; all failures and valid-empty results fall back to the portal-selected semester.
- Promoted future semesters are the sole current semester and do not inherit the previous semester calendar dates.
- Response validation now distinguishes authentication expiry, invalid structure, valid empty, and valid non-empty schedules. Invalid responses fail before cache replacement.
- Repository cache replacement no longer changes `viewedSemesterId`; catalog saves enforce one incoming current semester while preserving cache metadata.
- `DirectLoginViewModel` now has separate `downloadSemester` and `viewSemester` actions. Download success caches without selecting; viewing is limited to current/cached semesters.

## Files

- `app/src/main/java/com/glut/schedule/service/parser/AcademicSemesterParser.kt`
- `app/src/main/java/com/glut/schedule/service/academic/AcademicSemesterImportService.kt`
- `app/src/main/java/com/glut/schedule/service/academic/AcademicSemesterProbePlanner.kt`
- `app/src/main/java/com/glut/schedule/data/repository/ScheduleRepository.kt`
- `app/src/main/java/com/glut/schedule/ui/pages/DirectLoginViewModel.kt`
- `app/src/main/java/com/glut/schedule/ui/pages/DirectLoginScreen.kt`
- `app/src/test/java/com/glut/schedule/AcademicSemesterCatalogTest.kt`
- `app/src/test/java/com/glut/schedule/AcademicSemesterResponseValidatorTest.kt`
- `app/src/test/java/com/glut/schedule/AcademicSemesterImportServiceTest.kt`
- `app/src/test/java/com/glut/schedule/AcademicSemesterProbePlannerTest.kt`
- `app/src/test/java/com/glut/schedule/ScheduleRepositoryTest.kt`
- `app/src/test/java/com/glut/schedule/MultiSemesterUiContractTest.kt`

## RED/GREEN evidence

- Repository/validator RED: `AcademicSemesterResponseKind` and `classify` were unresolved; focused command exited 1. GREEN: `ScheduleRepositoryTest` + `AcademicSemesterResponseValidatorTest`, `BUILD SUCCESSFUL`.
- Import service RED: `AcademicSemesterImportPayload.responseKind` was unresolved; focused command exited 1. GREEN: login/random/valid-empty/valid-non-empty service tests passed with MockWebServer.
- Catalog RED: `parseCatalogPlan` was unresolved; focused command exited 1. GREEN: both campuses passed spring-to-autumn and autumn-to-next-spring tests with no far-future exposure.
- Probe planner RED: `AcademicSemesterProbePlanner` was unresolved; focused command exited 1. GREEN: non-empty promotion and failure/empty fallback tests passed.
- DirectLogin RED: three contract tests failed for missing split actions and probe wiring. GREEN: all six `MultiSemesterUiContractTest` tests passed.
- Full verification: JDK 17 `./gradlew.bat testDebugUnitTest` exited 0 with 328 tests, 0 failures, 0 errors, and 1 skipped test (`BUILD SUCCESSFUL`).

## Commit

- Implementation: `28b33b61ebbfa4d0a379b69a4daebe969a05485e` (`完善多学期探测与缓存校验`)

## Concerns

- Service behavior is covered with representative recognized HTML and MockWebServer; live portal markup/network behavior was not exercised in unit tests.
- The build retains an existing OkHttp deprecated API warning in `DirectLoginViewModel`; it is unrelated to this task.
- No Compose layout redesign or release version bump was included.
