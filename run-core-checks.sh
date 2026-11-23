#!/usr/bin/env sh
set -eu
OUT="${TMPDIR:-/tmp}/scrim-room-core-checks.jar"
kotlinc \
  app/src/main/java/dev/lolokeksu/scrimroom/model/Models.kt \
  app/src/main/java/dev/lolokeksu/scrimroom/model/ProfileValidator.kt \
  app/src/main/java/dev/lolokeksu/scrimroom/match/MatchEngine.kt \
  core-tests/MatchEngineChecks.kt -include-runtime -d "$OUT"
java -jar "$OUT"
