# AERIVA Security and Privacy Skill
Protect connectivity, usage, location, account, and diagnostics.

## Threats
Consider malicious apps, backend compromise, unauthorized database access, abusive contributors, leaked logs, insecure local storage, replayed sync payloads, excessive permissions, and location disclosure.

## Rules
Use least privilege, Android Keystore where appropriate, backend authorization and RLS, untrusted-input validation, minimized location precision, minimized retention, and strict separation of personal and aggregate data.

Never log secrets, tokens, passwords, private messages, or unnecessary precise locations.

Request permissions when needed and explain their purpose. If denied, keep unrelated features usable.

Before adding telemetry document event, fields, purpose, retention, access, whether it leaves the device, and consent.
