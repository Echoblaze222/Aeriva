# AERIVA Data Usage Intelligence Skill
Data usage management is a core AERIVA product pillar.

## Capabilities
Where Android permits:
- mobile and Wi-Fi usage
- per-app usage
- foreground/background usage
- upload/download breakdown
- daily/weekly/monthly history
- budgets and reset dates
- warning thresholds
- trends
- usage forecasting
- data-waste detection
- high-consumption app identification
- data-saving recommendations

## Product questions
AERIVA should help answer: How much data was used? Which apps used it? When? Was it foreground/background? Is it unusual? How much budget remains? When might the budget run out? What can reduce waste?

## Monitoring vs control
Monitoring is not control. If Android permits observation but not direct restriction, say so and link the user to system controls. Never claim AERIVA blocked data unless it actually did.

## Forecasts
Forecasts are estimates. Account for recent rate, remaining budget, remaining period, and insufficient history.

## Waste detection
Start with explainable rules such as background spikes, unusual usage, repeated large transfers, and deviation from an app's baseline. Do not invent savings figures.

## Privacy and efficiency
Detailed personal usage stays local by default. Any backend collection must have an explicit purpose, fields, retention, aggregation, and consent model. Monitoring must not consume excessive battery or data.
