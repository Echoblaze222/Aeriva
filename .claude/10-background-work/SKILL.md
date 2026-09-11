# AERIVA Background Work Skill
Background monitoring must respect Android restrictions and battery limits.

Prefer event-driven callbacks, WorkManager for deferrable work, foreground services only when genuinely required and user-visible, bounded scheduling, and batching.

Potential triggers include network changes, boot, data-budget checks, and synchronization. Verify current Android restrictions for each.

Every background feature should document trigger, frequency, duration, network cost, battery cost, and fallback.

Never create a hidden background service just to make a feature appear automatic.
