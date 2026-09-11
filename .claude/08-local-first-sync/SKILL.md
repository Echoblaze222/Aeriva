# AERIVA Local First and Sync Skill
AERIVA must remain useful during poor or absent connectivity.

## Local first
Persist important state locally, render cached data immediately, sync asynchronously, and label stale data honestly.

## Sync
Handle offline writes, reconnect, retries, backoff, idempotency, conflicts, partial failure, authentication expiry, and server rejection. Never blindly overwrite newer local state.

Represent local-only, queued, syncing, synchronized, failed, retrying, conflict, and stale states where applicable.

Supabase may provide Auth, Postgres, RLS, Realtime, Edge Functions, and Storage where justified. Do not make every UI interaction depend on a live backend.

Never disguise cached data as live data.
