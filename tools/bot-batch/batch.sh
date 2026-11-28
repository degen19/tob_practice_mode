#!/bin/sh
# batch.sh <room> <params-file> <log>: one server run, n bot teams (one per params line) in their own <room> (maiden | nylo).
# Prints each team's result line. Env passes through (TOB_TRIO_TRACE, TOB_HIT_TRACE, TOB_NYLO_POLICY, TOB_TRIO_SIZE...).
# Run from the repo root after `./gradlew -q :server:app:classes`, with PostgreSQL up.
ROOM=$1
PARAMS=$(realpath "$2")
LOG=$(realpath -m "$3")
N=$(wc -l < "$PARAMS")
while pgrep -f "[G]ameServerKt" >/dev/null; do sleep 2; done
rm -f .data/recordings/${ROOM}*-*
(GAME_TICK_MS=60 TOB_TRIO=$N TOB_TRIO_ROOM=$ROOM TOB_TRIO_PARAMS="$PARAMS" timeout 1800 ./gradlew -q :server:app:run > "$LOG" 2>&1 &)
sleep 30
while [ "$(grep -ac "$ROOM\(cas\)\? trio" "$LOG")" -lt "$N" ] && pgrep -f "[G]ameServerKt" >/dev/null; do sleep 3; done
pkill -f "[G]ameServerKt"
sleep 2
grep -a "$ROOM\(cas\)\? trio" "$LOG"
