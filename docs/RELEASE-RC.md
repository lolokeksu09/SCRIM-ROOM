# SCRIM ROOM 1.0.0-rc.2 — release gate

This is an invited-club release candidate, not a public launch.

## Audit and remaining blockers

| Area | Source review | Required launch evidence |
|---|---|---|
| Account | Recovery rotates opaque token; moderator approval remains manual | Verify ownership policy; real recovery drill; never disclose credentials |
| Matchmaking | Region/session isolation, role feasibility, private Elo | Pilot sample; no claim of improved win probability |
| Match lifecycle | Ready deadline, captain consensus, evidence, disputes | Ten physical clients in real custom game; moderator rehearsal |
| Community | Exact ID search, mutual links, complaints | Abuse review and privacy policy |
| Competitions | Fixed rosters, single-elimination cup, manual results | Four real teams; moderation |
| Notifications | Foreground-service polling, inbox | Physical-device battery/permission test; no guaranteed push |
| Operations | SQLite backup, readiness, best-effort shared-key audit | Restore drill, encrypted off-host backups, individual moderator access before broader rollout |
| Client | RC onboarding/navigation; debug signing is not release signing | Emulator CI, physical device, verified release certificate |
| Hosting | Local-only compose port | Dedicated hostname, HTTPS, external smoke; no production service yet |

Known limits: no Riot account ownership verification or official telemetry; no automatic lobby creation; waiting entries expire after 90 seconds without authenticated queue activity; assigned matches still follow their own lifecycle; single SQLite worker; shared moderator key; no formal privacy/legal review. Long lists remain bounded by API where applicable, not a measured performance optimization.

## Release APK

Only repository-owner workflow dispatch on main. Configure existing release signing key in GitHub secrets: SCRIM_KEYSTORE_B64, SCRIM_KEYSTORE_PASSWORD, SCRIM_KEY_ALIAS, SCRIM_KEY_PASSWORD, SCRIM_CERT_SHA256 (independently recorded 64-character SHA-256 fingerprint). Never generate a substitute key for an existing release identity or commit a private key. If no release identity exists, generate and securely back up a dedicated key offline first. A debug installation cannot be updated by a differently signed release; uninstalling removes local data, so preserve recovery code and data before migration. The manual workflow builds, signs, verifies certificate and uploads APK + SHA256SUMS. Publish only after all gates pass.

## Deployment (isolated from Ventry)

Use a dedicated approved hostname pointing to the VDS; do not change existing Ventry routes. Keep port 8765 loopback-only. From the repository root on the host, inspect compose configuration and existing port usage; create a protected non-repository environment file containing SCRIM_ADMIN_KEY (32+ random characters). Use the same key across restarts. Run:

```sh
docker compose --env-file /etc/scrim-room.env -f server/compose.yml up -d --build --wait
curl --fail --silent http://127.0.0.1:8765/readyz
```

Configure only the dedicated Caddy virtual host using server/deploy/Caddyfile.example, validate before reload, confirm external HTTPS /readyz and /admin. Do not expose the administrator key in logs or commit it. No live deployment is performed by this document.

Before each upgrade, run the backup CLI described in README; record source revision, image ID, backup checksum, integrity and restore test. Revert to prior image only if schema compatibility is confirmed; restoring old DB loses newer writes and may resurrect tokens. No destructive rollback is automatic.

## RC.2 safeguards
Compose defaults to closed registration (`SCRIM_REGISTRATION_OPEN=0`); existing accounts and recovery remain available. Set the variable to `1` only for an authorized intake window, recreate the service, then close registration again. This is not an invitation-code system; account ownership remains a manual gate. Request bodies are bounded before JSON/multipart parsing (32 KiB normal, 4 MiB + 64 KiB multipart for evidence). Validation errors do not reflect submitted input. OpenAPI is disabled. Release workflow requires successful debug/API, emulator, browser and container workflows on the exact commit before signing. Container CI runs on disposable test data only; it does not touch the VDS.
