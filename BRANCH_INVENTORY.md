# Branch inventory

Author: Agent 4 (CI, quality and repository hygiene). Evidence date: 2026-10-25.
Repository: Echoblaze222/Aeriva. Compared against `main` @ `9cff60cd0544` (PR #4).
Nothing was deleted, tagged or moved. All recommendations are proposals for the Team Leader.

## Method

- Full clone of all remote branches, then `git rev-list --left-right --count main...branch` for ahead and behind, `git merge-base --is-ancestor` for merged status, `git cherry` for patch equivalence, and a per file blob comparison against `main` for content already present. CONFIRMED, computed locally.
- Purpose is inferred from the branch name and the tip commit subject. Treat it as LIKELY, not as a statement by the author.
- Totals: 46 remote branches (including `main`), 0 tags in the repository.
- Merged means the branch tip is an ancestor of `main`. Squash or re-applied work shows as not merged even if the content is in `main`, which is why the last columns exist.
- Content columns: files added or modified on the branch since its merge base, how many are byte identical in `main`, how many do not exist in `main`.

## Summary

| Recommendation | Count |
|---|---|
| Keep | 10 (including `main`) |
| Candidate for deletion (merged, no unique commits) | 9 |
| Archive by tag, then candidate for deletion | 27 |

Proposed archive tag convention: `archive/<branch-name>` pointing at the branch tip, created by the leader before any deletion. No tag was created.

## Findings the leader should see first

1. `phase-4-reducer-stale-callback-fix` is not fully merged. PR #3 merged its earlier head. Three later commits (`f6079e9`, `baeb213`, `3709667`, dated 2026-10-22 and 2026-10-25) are on the branch and not in `main`: VPN underlying transport reporting, brand colour token alignment, mapper tests, and the `vpnUnderlyingTransport` default removal. CircleCI is green at `3709667` (run `b571ccbf-74e1-4fed-8086-68009632a407`). Do not delete this branch. CONFIRMED.
2. `network-advisor-phase-a-spec` (1 commit, 1 file) is the spec every Advisor agent is told to read and is not merged. Keep until it is merged or archived deliberately. CONFIRMED.
3. `phase-5-measurement-validation-harness` holds 18 commits and 17 files that exist nowhere in `main`. Highest unmerged volume. Keep. CONFIRMED.
4. Every branch created for the current agent round sits on `9cff60c` (up to date with `main`), so none is stale.
5. Many `phase-3*` and `phase-4*` audit branches are 25 to 35 commits behind `main` with documentation only content that is absent from `main`. Deleting them loses that content, so archive by tag first.

## Inventory

Columns: Ahead and Behind are commit counts versus `main`. Base is the merge base. Files is changed files, then in parentheses identical in `main` / absent from `main`.

### Keep

| Branch | Head | Base | Ahead | Behind | Merged | Files | Purpose (inferred) |
|---|---|---|---|---|---|---|---|
| main | 9cff60c | n/a | 0 | 0 | n/a | n/a | Integration branch, PR #4 head |
| audit/network-monitor-seam-impl | f14fb82 | 9cff60c | 1 | 0 | no | 3 (0 / 2) | Extract platform callback seam, Agent 1 area |
| audit/network-monitor-seam-report | 4320d9b | 9cff60c | 4 | 0 | no | 4 (0 / 4) | Network monitor reliability seam report, Agent 1 |
| design/advisor-ui-and-brand-signoff | 8111bda | 9cff60c | 4 | 0 | no | 4 (0 / 4) | Advisor UI spec and brand sign off, Agent 6 |
| docs/data-usage-spec | f921090 | 9cff60c | 1 | 0 | no | 1 (0 / 1) | Data usage phase spec, Agent 3 |
| docs/measurement-endpoint-research | a376830 | 9cff60c | 1 | 0 | no | 1 (0 / 1) | Measurement endpoint and client research, Agent 5 |
| feature/advisor-a1-core | 6a2ad0e | 9cff60c | 2 | 0 | no | 11 (0 / 11) | Network Advisor A1 pure core, Agent 2 |
| network-advisor-phase-a-spec | f350874 | ebee5f6 | 1 | 3 | no | 1 (0 / 1) | Network Advisor Phase A specification |
| phase-4-reducer-stale-callback-fix | 3709667 | ebee5f6 | 3 | 3 | no (partly, see finding 1) | 15 (0 / 2) | VPN transport, tests, default removal after PR #3 |
| phase-5-measurement-validation-harness | 53ed0e0 | e3f70a4 | 18 | 35 | no | 17 (0 / 17) | Real device validation harness, docs, fixtures, checker |

Note: `audit/network-reliability-tests` is merged by PR #4 and appears in the deletion list below. The leader decision on it was to merge, which has happened (0 commits ahead).

### Candidate for deletion (merged into `main`, 0 unique commits)

History stays in `main`. Deleting the pointer loses nothing. CONFIRMED by `merge-base --is-ancestor`.

| Branch | Head | Ahead | Behind | Purpose (inferred) |
|---|---|---|---|---|
| audit/network-reliability-tests | 7e3a6ef | 0 | 2 | Merged by PR #4 |
| circleci-recovery | 5f1964a | 0 | 44 | Repaired CircleCI config, merged |
| phase-2-android-platform-audit | 1f83bb8 | 0 | 39 | Phase 2 platform audit, merged by PR #1 |
| phase-3a-domain-model | 6e8cd86 | 0 | 36 | Phase 3A domain model, merged by PR #2 |
| phase-3b-measurement-engine | 727b2a9 | 0 | 30 | Measurement engine fix, ancestor of `main` |
| phase-4-controlled-integration | 52c26ce | 0 | 14 | Integration branch reconcile |
| phase-4-engine-foundation-integration | 7731aa4 | 0 | 17 | Stage 4 documentation |
| phase-4-measurement-foundation | fdf5440 | 0 | 25 | Measurement foundation default fix |
| phase-4-test-hardening | d663b05 | 0 | 10 | Test hardening, import fix |

### Archive by tag, then candidate for deletion (unmerged)

All below have commits that are not in `main`. Most are audit, review or planning documents. The files absent from `main` would be lost on deletion, hence tag first. Whether any is superseded by a later document is UNCONFIRMED for every row, since no content review was done.

#### Phase 3

| Branch | Head | Base | Ahead | Behind | Files (identical / absent) | Purpose (inferred) |
|---|---|---|---|---|---|---|
| phase-3b-android-contract | a67b74d | e3f70a4 | 2 | 35 | 4 (1 / 2) | Android measurement contract, DNS classification |
| phase-3b-android-contract-review | 27c0f2f | e3f70a4 | 3 | 35 | 5 (2 / 3) | Contract fact check |
| phase-3b-android-contract-final-review | 9d37ce5 | e3f70a4 | 4 | 35 | 6 (2 / 4) | Final contract review |
| phase-3b-android-measurement | fc004b6 | e3f70a4 | 1 | 35 | 3 (0 / 1) | Capability report and classifier |
| phase-3b-engine-integration-audit | 20dd1c5 | e3f70a4 | 1 | 35 | 1 (0 / 1) | Independent engine audit |
| phase-3b-engine-test-gate | 4ed691f | e3f70a4 | 3 | 35 | 17 (8 / 2) | Engine test gate report |
| phase-3b-measurement-tests | 8cbab34 | e3f70a4 | 4 | 35 | 12 (6 / 1) | Test plan with CI evidence |
| phase-3b-repository-state-reconciliation | 29e01e3 | e3f70a4 | 1 | 35 | 1 (0 / 1) | Branch state reconciliation audit |
| phase-3b-validation | ad461a5 | e3f70a4 | 1 | 35 | 1 (0 / 1) | Validation and test report |
| phase-3c-android-validation-matrix | 07c0ab4 | e3f70a4 | 4 | 35 | 6 (2 / 4) | Real device validation matrix, docs only |

#### Phase 4

| Branch | Head | Base | Ahead | Behind | Files (identical / absent) | Purpose (inferred) |
|---|---|---|---|---|---|---|
| phase-4-agent4-integration-tests | 24866db | 9353d50 | 1 | 7 | 2 (1 / 0) | Debounce timing and repository tests. One commit is patch equivalent in `main` |
| phase-4-android-network-state | 800b2ec | 545a961 | 4 | 26 | 6 (2 / 0) | Network state pipeline test fixes |
| phase-4-android-platform-spec | 458e32e | 545a961 | 1 | 26 | 1 (0 / 1) | S3 permission and platform spec |
| phase-4-android-platform-spec-audit | 8610a57 | 545a961 | 2 | 26 | 2 (0 / 2) | Audit of that spec |
| phase-4-android-platform-spec-corrected | 6e3ce0f | 545a961 | 2 | 26 | 1 (0 / 1) | Six corrections applied |
| phase-4-cross-cutting-decisions | 8a56dc7 | e3f70a4 | 2 | 35 | 2 (0 / 2) | Cross cutting technical decision contract |
| phase-4-engine-foundation-reconciliation | b8e8d9a | fdf5440 | 2 | 25 | 1 (0 / 1) | Reconciliation plan |
| phase-4-engine-hardening | 158f169 | 727b2a9 | 2 | 30 | 3 (0 / 0) | Cancellation, timing and timeout fixes. One commit patch equivalent in `main` |
| phase-4-implementation-readiness | e86f26a | e3f70a4 | 1 | 35 | 1 (0 / 1) | Reconcile validation plan and architecture |
| phase-4-jitter-independent-audit | 7ee1331 | 545a961 | 6 | 26 | 8 (5 / 1) | Jitter independent audit |
| phase-4-measurement-architecture | f279476 | e3f70a4 | 2 | 35 | 1 (0 / 1) | Measurement architecture document |
| phase-4-network-state-audit | b4f0e6c | 545a961 | 3 | 26 | 6 (1 / 1) | Network state integration audit |
| phase-4-permission-adapter | e912fb7 | 76651aa | 2 | 21 | 7 (7 / 0) | Permission adapter. All 7 changed files are byte identical in `main`, so the content is already merged. Strongest deletion candidate in this group, after tagging |
| phase-4-real-device-validation | 3589da4 | 727b2a9 | 1 | 30 | 1 (0 / 1) | Real device validation plan |
| phase-4-s3-implementation-authorization | bc4b60d | e3f70a4 | 1 | 35 | 1 (0 / 1) | S3 implementation authorization gate |
| phase-4-s3-integration-readiness-audit | 835d27f | 7731aa4 | 1 | 17 | 1 (0 / 1) | Integration readiness audit |
| phase-4-test-gate | 4adf30c | 545a961 | 5 | 26 | 7 (5 / 0) | Jitter test gate report |

A file counted as neither identical nor absent differs from the version in `main`. `main` may hold a newer version. Not reviewed.

## Suggested order of operations for the leader

1. Decide on `phase-4-reducer-stale-callback-fix` (finding 1) before anything else. It is the only branch holding recent, CI green, unmerged production work other than the Advisor branches.
2. Create `archive/*` tags for the 27 branches in the third table, then delete the 9 merged branches. Deleting merged branches does not need tags.
3. Delete the archived branches only after the tags exist.
4. Re-run this inventory after the Advisor merges, since 7 active branches will become merged.

## Limits

- Branch protection, open pull requests and branch ownership were not queried. A branch can be the head of an open PR that is not visible here.
- Purpose and supersession are inferred from names and tip commit subjects only.
- Remote only. Local only branches on any developer machine are not covered.
