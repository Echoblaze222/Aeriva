# AERIVA Dependency Governance Skill
Before adding any dependency ask:
- Is the capability already in Android/Kotlin/AndroidX?
- Is the dependency maintained?
- Is its license suitable?
- What permissions does it require?
- What size/startup/runtime cost does it add?
- Does it touch sensitive data?
- Can the feature be implemented more simply?

Prefer official libraries. Avoid duplicate libraries. Pin versions using the project's existing dependency management. Review transitive dependencies for security-sensitive features.

Do not install a plugin, skill, SDK, or library merely because it sounds useful. Establish the concrete requirement first.
