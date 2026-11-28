#!/bin/sh
# check.sh <verzikSim dir> <team>[/way] <raids>: the server's SoteEngine against the sim, seed by seed (s1..s<raids>).
# Prints the first difference of any raid that differs, then how many matched. Build first: ./gradlew -q :content:raids:tob-bloat:classes
SIM=$1; TEAM=$2; N=${3:-50}
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
CP="$ROOT/content/raids/tob-bloat/build/classes/kotlin/main:$(find ~/.gradle/caches -name 'kotlin-stdlib-2*.jar' ! -name '*sources*' | head -1)"
SEEDS=$(seq -f 's%g' 1 "$N" | tr '\n' ' ')
TMP=$(mktemp -d)
node "$(dirname "$0")/slog.mjs" "$SIM" "$TEAM" $SEEDS > "$TMP/js.log"
java -cp "$CP" org.rsmod.content.raids.tob.bloat.sote.SoteCheckKt "$TEAM" $SEEDS > "$TMP/kt.log"
ok=0; bad=0
for s in $SEEDS; do
  awk -v s="$s" '/^== /{on=($2==s)} on' "$TMP/js.log" > "$TMP/a"; awk -v s="$s" '/^== /{on=($2==s)} on' "$TMP/kt.log" > "$TMP/b"
  if cmp -s "$TMP/a" "$TMP/b"; then ok=$((ok + 1)); else bad=$((bad + 1)); [ $bad -le 2 ] && { echo "--- $s differs (sim < > server):"; diff "$TMP/a" "$TMP/b" | head -8; }; fi
done
echo "team $TEAM: $ok of $N raids identical"
rm -rf "$TMP"
