# AERIVA Database and Preferences Skill
Use Room for structured history and relational state. Use DataStore for small preferences and configuration.

## Room
Use explicit entities/DAOs, migrations, indexes based on real queries, transactions where required, Flow where useful, and no main-thread database access.

## DataStore
Suitable for onboarding state, preferences, thresholds, and small settings. Do not use it as a relational history store.

Plan for migration failure, invalid preferences, missing records, partial writes, and process death. Do not silently discard user data.

Repositories should expose product operations rather than arbitrary database details.

Test migrations, DAOs, repositories, defaults, invalid values, transactions, and offline persistence.
