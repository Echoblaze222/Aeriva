# AERIVA validation assets (Phase 5)

Language-neutral test infrastructure for the measurement validation harness. Nothing here is production code and nothing here runs on a device.

| Path | Purpose |
|---|---|
| `schema/evidence-record.schema.json` | JSON Schema (draft 2020-12) for one validation evidence record. Closed property sets make it a privacy control. |
| `fixtures/*.json` | Golden fixtures. SYNTHETIC test vectors, not real measurements. |
| `fixtures/evidence-examples/valid/` | Synthetic evidence record examples that must validate. |
| `tools/validate_validation_assets.py` | Checks the schema, checks every fixture against an independent reference oracle, proves the schema rejects 15 negative cases, and lints the Phase 5 documents. |

Run (needs python3 and `pip install jsonschema`):

```
python3 validation/tools/validate_validation_assets.py
```

The oracles are written from the documented semantics (Phase 3B code at `phase-3b-measurement-engine@727b2a91` and `PHASE_4_CROSS_CUTTING_TECHNICAL_DECISION_CONTRACT.md`), not from the Kotlin implementation. A mismatch between an oracle and Kotlin output is a finding to investigate.

Real evidence records from physical-device runs are not committed here. See `PHASE_5_TEST_DATA_AND_EVIDENCE_SCHEMA.md`, section 5.
