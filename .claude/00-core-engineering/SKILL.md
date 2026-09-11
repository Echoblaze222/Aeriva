# AERIVA Core Engineering Skill
Apply these rules to every AERIVA task.

## Non-negotiable
1. Inspect the repository, architecture, tests, Gradle configuration, and relevant documentation before editing.
2. Do not rewrite working systems without a concrete reason.
3. Do not invent APIs, Android capabilities, metrics, test results, or completed functionality.
4. Prefer official Android and Kotlin APIs when sufficient.
5. Add dependencies only when justified.
6. Treat offline behavior, poor connectivity, battery, privacy, and failure recovery as first-class concerns.
7. Every user-triggered operation must expose appropriate processing, success, failure, cancellation, offline, permission, or unavailable states.
8. Prevent duplicate submissions and repeated destructive actions.
9. Never use fake production data.
10. Never claim a network improvement that was not measured.
11. Do not use the em dash character in AERIVA documentation, code comments, or UI copy.
12. Do not use emojis in AERIVA engineering documentation or UI.
13. A feature is not complete because a screen or button exists.

## Workflow
Before editing: identify files, platform constraints, persistence, synchronization, security, battery, performance, and tests.

After editing: run relevant tests, compile/build validation, configured static analysis, inspect the diff, and report what changed, what was verified, what was not verified, risks, and next steps.

## Definition of done
Implementation, persistence, lifecycle behavior, error states, tests, and platform constraints are addressed as applicable.
