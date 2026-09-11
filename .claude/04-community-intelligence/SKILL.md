# AERIVA Community Connectivity Intelligence Skill
Build shared connectivity intelligence without making users trackable.

## Separation
Keep personal measurements, community aggregates, and future commercial analytics separate. Never expose raw personal measurements by default.

## Privacy
Prefer coarse spatial and temporal aggregation. Define minimum sample thresholds, independent-contributor requirements, retention, freshness, confidence, and outlier handling. Do not expose exact home/work locations or movement histories.

## Evidence
Recommendations should consider sample count, contributor diversity, measurement age, network/provider, activity type, variance, and confidence.

## Abuse
Protect against synthetic measurements, spoofing, flooding, single-user domination, stale evidence, and ranking manipulation.

## Example
Good: "Network A has shown better stability in this area based on 42 recent measurements."
Bad: "John's phone says Network A is best at his house."

AERIVA should never pretend to know what it does not know.
