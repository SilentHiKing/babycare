# Repository Guidelines

## Project Structure & Module Organization
- `app/`: Android application module (activities, fragments, navigation, UI layouts).
- `babydata/`: data layer (Room entities, DAOs, repository, database migrations).
- `common/`: shared resources and utilities (themes, drawables, extensions, common utils).
- `components/`: reusable UI widgets and base classes (view binding helpers, dialogs, toolbars).
- `baby_recyclerview/`: customized RecyclerView adapters and helpers.
- `tools/`: supporting scripts (language tooling under `tools/language/`).
- Root Gradle config in `build.gradle.kts`, version catalog in `gradle/libs.versions.toml`.

## Build, Test, and Development Commands
- `./gradlew assembleDebug` - build a debug APK.
- `./gradlew installDebug` - install the debug APK on a device/emulator.
- `./gradlew test` - run JVM unit tests (`src/test/java`).
- `./gradlew connectedAndroidTest` - run instrumentation tests (`src/androidTest/java`).
- `./gradlew updateAllLanguages` - refresh i18n resources.
- `./gradlew generateLanguageJson` - export language data to JSON.
- `./gradlew fetchInternationalLanguageList` - pull supported language lists.

## Coding Style & Naming Conventions
- Kotlin/Java use 4-space indentation and standard Android conventions.
- Package names follow `com.zero.*`; keep classes in the correct module package.
- Resource files use snake_case (e.g., `layout_event_detail_diaper.xml`).
- Shared extensions live under `common/src/main/java/com/zero/common/ext`.

## Testing Guidelines
- Unit tests: `*/src/test/java` (JUnit).
- Instrumentation tests: `*/src/androidTest/java` (AndroidX test + Espresso).
- Name tests `*Test` and cover new data logic in `babydata` or new view model behavior.
- No explicit coverage target is defined; prioritize critical paths and regressions.

## Commit & Pull Request Guidelines
- Commit history favors short, single-line summaries (often release or feature labels like `v6`).
- Keep commits focused and describe the change in one concise sentence.
- PRs should include: a clear description, testing notes (commands run), and screenshots for UI changes.

## Security & Configuration
- Keep local SDK paths and secrets in `local.properties`; do not commit it.
- Use module `proguard-rules.pro` for release rules when needed.


---

# AI / Codex Strong Constraints (Mandatory)

The following rules are **hard constraints** for all AI / Codex agents
operating in this repository.

Violating any rule below is considered an **incorrect solution**.
If a request conflicts with these constraints, the agent must explain
the conflict and propose a compliant alternative instead of producing
invalid code.

---

## 1. Module Dependency & Boundary Constraints

- `common/` and `components/` must never depend on `app/` or any
  business-specific modules.
- `babydata/` must never depend on UI modules (`app/`, `components`).
  It may depend on `common/` only for shared utilities or base abstractions.
- `app/` is the top-level module and may depend on other modules, but
  no change may introduce circular dependencies.
- Cross-module shared contracts (interfaces, base models) must be
  defined in `common/` (or a dedicated `domain/` module if explicitly introduced).
- Business logic, feature-specific constants, and feature resources
  must not be placed in `common/` or `components/`.

The correct module must always be chosen based on responsibility.

---

## 2. Resource, Theme, and Localization Constraints

- All user-facing text must be defined in `strings.xml`.
  Hard-coded strings in Kotlin, Java, or XML layouts are forbidden.
- Resource files must use `snake_case` naming and existing prefix
  conventions, including but not limited to:
  - `layout_*.xml`
  - `ic_*.xml`
  - `bg_*.xml`
  - `shape_*.xml`
  - `color_*.xml`
  - `anim_*.xml`
- Themes, colors, text appearances, and fonts must be reused from
  `common/` whenever available.
- Styles, colors, or themes must not be duplicated inside `app/`.

Any generated UI code must comply with these constraints.

---

## 3. Threading, Lifecycle, and Memory Safety Constraints

- Disk I/O, database access, and network operations must not run on
  the main thread.
  Use `Dispatchers.IO` or the project’s standard background dispatcher.
- Coroutines launched from `Activity` or `Fragment` must be
  lifecycle-aware:
  - Use `lifecycleScope` or `viewLifecycleOwner.lifecycleScope`
  - `GlobalScope` is strictly forbidden.
- Custom `View` implementations:
  - Must not hold long-lived references to `Context` or `Activity`
  - Must unregister listeners or callbacks in `onDetachedFromWindow`
    when applicable
- `RecyclerView.Adapter` and `ViewHolder` implementations must not
  keep strong references that could cause memory leaks.
- Any asynchronous logic introduced during refactoring must explicitly
  consider lifecycle cancellation and cleanup.

Memory safety and lifecycle correctness are mandatory requirements.

---

## Enforcement Checklist (For AI Agents)

Before producing a final answer:
- Verify module placement and dependency direction.
- Verify no hard-coded user-facing strings exist.
- Verify threading and lifecycle correctness for all async logic.
- Verify no memory leaks are introduced by views, adapters, or callbacks.
