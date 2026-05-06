# Repository Guidelines

## Project Structure & Module Organization

This is a single-module Android app under `app/`. Main Kotlin sources live in `app/src/main/java/com/glut/schedule/`.

- `data/model`, `data/local`, `data/repository`, `data/settings`: domain models, Room persistence, repositories, and DataStore settings.
- `service/academic` and `service/parser`: GLUT academic-system WebView/API import and timetable parsing.
- `ui/components`, `ui/pages`, `ui/theme`: Jetpack Compose UI, screens, and theme.
- Unit tests are in `app/src/test/java/com/glut/schedule/`.
- Debug import captures used for investigation may be stored in `debug/`; generated build outputs stay under `app/build/`.

## Build, Test, and Development Commands

Use JDK 17. If Gradle uses a newer Java version, set:

```powershell
$env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-17.0.19.10-hotspot"
```

Key commands:

```powershell
.\gradlew.bat testDebugUnitTest      # run local unit tests
.\gradlew.bat assembleDebug          # build debug APK
.\gradlew.bat assembleRelease        # build release APK
.\gradlew.bat clean                  # remove Gradle build outputs
```

APK outputs: `app/build/outputs/apk/debug/app-debug.apk` and `app/build/outputs/apk/release/release_<version>.apk`.

## Coding Style & Naming Conventions

Use Kotlin idioms and the existing package layout. Keep Compose functions in PascalCase, ViewModels named `*ViewModel`, factories named `*ViewModelFactory`, and tests named `*Test`. Prefer small, focused functions over broad refactors. Keep comments brief and only where they clarify non-obvious behavior.

## Testing Guidelines

Tests use JUnit 4 and `kotlinx-coroutines-test`. Add or update tests for parser behavior, repository persistence, week filtering, and import fallbacks. Run `.\gradlew.bat testDebugUnitTest` before handing off. For targeted checks, use:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.glut.schedule.AcademicImportTest"
```

## Commit & Pull Request Guidelines

Existing commits use concise summaries, often Chinese, such as `导入课表、UI、交互基本完善`. Keep commits short and outcome-focused. PRs should describe the user-visible change, list verification commands, mention APK/version changes, and include screenshots for UI changes.

## Release & Configuration Notes

Do not commit local machine paths except documented examples. After substantial feature work, bug-fix batches, or import-flow improvements, increment both `versionCode` and `versionName` in `app/build.gradle.kts` before producing a release package.
