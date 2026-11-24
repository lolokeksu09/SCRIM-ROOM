# SCRIM ROOM — final release candidate audit (RC.8)

Scope: exact RC.7 revision 692d747 and source changes for RC.8, no production access, no DNS changes. Historical audit issues and subsequent fixes are tracked here. A passed automated gate is not a live deployment or verified player identity.

| Area | RC.8 disposition | Remaining evidence or limitation |
|---|---|---|
| Match selection | Full queue role feasibility; bounded alternate cohorts; 120 average / 400 role Elo gap caps; batched history | Balanced roster search is heuristic, not exhaustive; pilot fairness and long-wait metrics |
| Lifecycle | Ready/active/dispute timeouts; requeue; phase checks also on lobby and evidence | Ten-player real game rehearsal; match is coordinated, not observed via Riot telemetry |
| Integrity | Distinct, non-reused evidence gate; moderator decisions and cancellation reasons; role-valid fixed rosters | Images are not proof of authenticity; collusion/edited screenshots remain possible |
| Account | Recovery rotation, Android Keystore, closed registration in compose, mandatory current review revision in production | Riot ID ownership and rank manual, account deletion/export workflow and token/device management not implemented |
| Privacy | Opt-out of new contact, no plaintext secret in audit, scheduled result-evidence retention | Profile images and historical hashes remain; no formal privacy policy, backup retention or secure erase |
| Moderator | Named key hashes, actor audit, revision checks and paginated accounts | Shared owner key; full moderator permissions; audit is best effort, not transactional |
| Server | Request body limits, two concurrent image decoders, worker-thread image normalization, bounded latency metrics | SQLite single-worker lock; no production load, proxy ingress limits, dependency lock/audit or HA validation |
| Client | Modular screens, compact UI, roster/graph/cup view, document preview | Real phone and accessibility checks, notification delivery under actual battery policies |
| Build | Debug CI and exact-commit signing gate | Signed artifact requires trusted signing secret and verified certificate; no physical installation |
| Deployment | Docker smoke and backup CLI | Host, DNS, TLS, restore drill and operator credentials deliberately not configured |

## RC.8 verification targets

- Missing, stale and malformed review revision denied; current revision accepted.
- Expired match rejects lobby and evidence before next maintenance tick.
- Retention removes old resolved-match image bytes and preserves historical digest, including legacy entries.
- Decoder overload returns retryable 503 and normal upload recovers after release.
- Existing API, Android 13/15, browser and Docker checks rerun on the exact final revision.

## Release decision

Do not tag 1.0.0 merely because CI is green. An invited closed pilot is the next operational phase, subject to owner approval and verified signature, real device, secured hosting, tested restore and real participants. Public registration and release are separate decisions. Source-only fixes in RC.8 are not production mutations.

## Measured RC.7 baseline

See [PERFORMANCE.md](PERFORMANCE.md) for exact CI results. 53 tests passed; 252 concurrent synthetic requests completed in 1.47 s; queue p95 69.55 ms, peak RSS 100.6 MiB, no 5xx. No claim of production SLA.

## Risk of retention on future upgrade

The scheduled job logically deletes result images older than 30 days belonging to resolved/cancelled matches, in batches of at most 100, and keeps SHA-256 fingerprints. Audit retention remains 90 days. Existing backups may still contain bytes. Before deploying, retain required evidence according to the operator's policy and create a protected backup. No VACUUM, physical file erasure, account deletion or backup destruction is performed.
