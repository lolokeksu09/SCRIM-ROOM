# SCRIM ROOM — Android + pilot server 0.4.0

**Independent organizer for private Wild Rift custom games.** Not affiliated with Riot Games. Does not provide a public alternative ranked ladder or access Riot's matchmaker. Only locally assigned rating seeds for balancing private teams, **not Riot MMR**.

## Delivered in 0.4.0

### Native Android client
- Kotlin / Jetpack Compose, AMOLED UI, minSdk 26, targetSdk 35.
- Local draft: Riot ID, region, GM+ rank in current **or** previous season, two distinct preferred roles, two screenshots (ranked season + champions).
- HTTPS server address configurable in profile. Switching host resets old authorization rather than transmitting the token to an unfamiliar server.
- Online registration with explicit consent; uploads PNG/JPEG/WEBP evidence (max 4 MB each), admin status refresh.
- Real multiuser queue: join, leave, refresh every 10 seconds while screen is in view, ten participants, team/role allocations, ready check, decline and 3-minute timeout.
- Local fictional queue/demo history **clearly separated** from online queue; no spoofed Riot wins or official scores.
- Local private storage for device token; backup disabled. This is a pilot authentication scheme, not Riot Sign On.

### Server
- Python FastAPI, SQLite, single process. 10 integration tests.
- Account registration with random opaque bearer token (only SHA-256 token hash on the server), unique Riot ID within a region, rank eligibility.
- Screenshot re-encoding to JPEG without source metadata, capped dimensions and byte size, maximum four images per participant.
- `PENDING_REVIEW` default. Only manual administrator approval after at least two images allows queue entry.
- Queue isolated by region; 5v5 role assignment and balance from **provisional rank-based seeds**, not verified ratings. Early waiting users have priority, with a lookahead up to 12 players for feasible role coverage.
- Ready check (`READY_CHECK` → `ACTIVE`) only when all 10 confirm. Declines or timeouts cancel matches and requeue eligible players according to policy.
- The game lobby and result are handled manually; admin can close active custom matches.
- `server/compose.yml` and `server/Dockerfile`; binds to `127.0.0.1:8765` for a separately configured HTTPS reverse proxy.

## Public GitHub repository

Source code is published at [lolokeksu09/SCRIM-ROOM](https://github.com/lolokeksu09/SCRIM-ROOM). The repository contains **only source code and test fixtures**. Never commit player screenshots, Riot ID submissions, database files, server administrator keys, real credentials or Android signing keys. The project is an unofficial custom-game organizer, not affiliated with Riot Games. The service is not ready to accept registrations until an HTTPS pilot server is deployed and privacy/moderation measures are in place.

## Build Android APK

GitHub Actions workflow `.github/workflows/android-debug.yml` runs server tests and builds **debug** APK for pushes to `main` or by manual `workflow_dispatch`.

Requirements: JDK 17, Gradle 8.9, Android SDK Platform 35. Local command:

```bash
gradle :app:assembleDebug --no-daemon
```

Expected output: `app/build/outputs/apk/debug/app-debug.apk`. A debug APK is automatically signed with Android's *debug certificate*, **not** a user-owned release signing key. No signed release is included.

GitHub Actions has successfully compiled and uploaded a **debug** APK. Real-device installation and interactive testing have NOT yet been performed. The online server is not deployed.

## Run the server locally

```bash
python3 -m venv .venv
. .venv/bin/activate
pip install -r server/requirements-test.txt
python -m pytest -q server/tests
export SCRIM_DB_PATH="$PWD/scrim-pilot.sqlite3"
export SCRIM_ADMIN_KEY="$(python3 -c 'import secrets; print(secrets.token_urlsafe(40))')"
uvicorn server.app:app --host 127.0.0.1 --port 8765 --workers 1
```

The Android application deliberately **refuses HTTP connections**, including localhost. To connect a device, deploy the server behind a valid public HTTPS hostname; do not expose port 8765 publicly. Do not reuse the existing `ventrylab.top` hostname without deliberate configuration.

Docker on a separately configured VDS, from the repository root:

```bash
export SCRIM_ADMIN_KEY="$(python3 -c 'import secrets; print(secrets.token_urlsafe(40))')"
docker compose -f server/compose.yml up -d --build
curl -s http://127.0.0.1:8765/healthz
```

For persistence across deployments, **store the key in a secret environment manager or non-committed `.env` file**. Docker restarts without the same key otherwise break moderator access. Expose the service only through Caddy/Nginx HTTPS. Back up the named Docker volume (SQLite DB and screenshot evidence). Use `--workers 1`; the in-process lock is not cross-worker.

## Moderator console

Open `/admin` on the configured HTTPS server. Enter the administrator key from the deployment secret store. The console does not persist the key or expose account data without authorization. Review the uploaded evidence, confirm identity independently, approve/reject applications and close completed active matches. An outdated review is rejected by the API. No production deployment is included.

## Manual moderation

Screenshots cannot prove account ownership on their own. Never approve automatically from an uploaded rank.

```bash
# Run in an administrator terminal; key must be available from the deployment's secret store.
curl -H "X-Admin-Key: $SCRIM_ADMIN_KEY" http://127.0.0.1:8765/v1/admin/accounts
# After verifying two screenshots using authorized moderation access:
curl -X POST -H "X-Admin-Key: $SCRIM_ADMIN_KEY" \
  http://127.0.0.1:8765/v1/admin/accounts/ACCOUNT_ID/approve
# After a manual custom match, clear its slot:
curl -X POST -H "X-Admin-Key: $SCRIM_ADMIN_KEY" \
  http://127.0.0.1:8765/v1/admin/matches/MATCH_ID/close
```

For screenshot retrieval use `GET /v1/admin/accounts/{account_id}/evidence/{slot}` with moderator key. Don't send the admin key to the Android app.

## Important limits before public release

- **No Riot Sign On** or proof that a submitted Riot ID belongs to the registrant. The moderator must confirm independently.
- No end-to-end custom lobby automation or Riot match result API. Teams enter Wild Rift custom games manually.
- No public leaderboard, push notification or recovery across device reinstall. Results require captain consensus or moderator resolution.
- The server is designed for a **controlled, invited pilot**, not arbitrary public traffic. Rate limiting, abuse protection, audit logging, account recovery, retention/deletion policy, are still required before an unrestricted launch.
- If login token is lost, the user cannot restore the same pilot account from another device yet.
- Screenshots are stored in a private SQLite volume, accessible to an administrator. Do not deploy without privacy disclosures, backup controls and a data retention plan.
- Role-based matching may wait when a region lacks a sufficient combination of players.

## Verification

```bash
./run-core-checks.sh
python -m pytest -q server/tests
```

Pure Kotlin engine has 19 standalone checks. Server API currently has 10 integration tests including 5v5 matching, unique roles, approvals, duplicate submissions, region isolation, declines, expiry and the role lookahead. These tests are **not** an Android compile or on-device test.

## Roadmap 0.3

- Result evidence uploads and moderator audit trail.
- Evidence deletion / retention controls and moderator audit trail.
- Observability, rate limiting, access recovery and secure invite-only pilot controls.
- APK compile/run evidence and a release-signed artifact (with a key belonging to the project owner).

## Security & deployment status

Public source code does **not** mean the registration service is publicly deployed. Player data belongs in private server storage with controlled administrator access; no screenshots or account records are checked into this repository. The Android debug APK is for testing only. The CI build has to pass before an APK can be described as available.

## Private match results
The assigned BARON player on each team is captain. Matching reports finalize atomically; disagreement blocks requeue until moderator resolution. Void results do not affect ratings. Private history is authenticated. Internal balancing uses symmetric Elo with K=24 and team means, not Riot MMR. Startup adds three tables without replacing existing tables. Back up the SQLite volume before upgrading. Historical closed matches are not retroactively scored.

## 0.4.0 pilot safeguards
Tesseract English/Russian recognition is optional and on-demand for previously uploaded evidence, with a 12-second deadline and one concurrent recognition per process. Suggestions are not verification; only an explicit player choice copies a unique rank into the selected season field. Statistics are suggestions only. Duplicate normalized image hashes across current account evidence are moderator-only flags, not automatic bans. Decline cooldown is 60 seconds, no-ready timeout cooldown 120 seconds, independent of skill estimate. Fixed-window quotas: 30 registrations/hour per client IP, 20 evidence requests/hour per account, 12 OCR requests/hour per account, 30 queue joins/minute per account. Configure trusted proxy correctly; these are pilot quotas, not DDoS protection. Match-found banner refreshes every 10 seconds while the activity is STARTED; no background push or foreground-service guarantee. Real screenshots and deployment still require verification.

## 0.5.0 — scheduled private game sessions
Moderators create regional windows (10–120 minutes, up to 100 places). Only approved participants in the region can reserve a place; overlapping windows and duplicate reservations are controlled by the server. Joining is explicit during the window and uses a separate queue scope, never mixed with ordinary matchmaking. Cancelling or ending a window removes waiting players, not assigned matches. A decline or timeout returns eligible participants only to their original open scope. Four new tables are additive; back up the SQLite volume before upgrade. No automatic external game launch or background reminders.

## 0.6.0 — Community Update
Approved players can look up exact Riot IDs in their own region, request mutual friendships, and invite accepted friends to sessions they have joined. Invitations do not reserve a seat; recipients explicitly sign up. Complaints are limited to shared active/disputed/closed matches from the last seven days, one complaint per reporter/target/match; moderator decisions require a reason and never automatically change Elo or suspend a player. The player's own confirmed complaint count is private; there is no public reputation leaderboard. Three additive SQLite tables are created on startup. Back up the existing volume before upgrades. No direct chat or background push.
