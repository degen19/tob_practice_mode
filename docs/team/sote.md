# Sotetseg

**Files:**
- `solo/SoteSolo.kt`: the room in the raid;
- `bloat/sote/*`: `SoteEngine` is a line-for-line port of verzikSim's `engine/sote.js` runSote, every scale (prefires,
  horn, mauls, ZCBs, orbs, the death ball, maze stalls on a phoenix necklace, shark and redemption skips, Lightbearer,
  thralls, P3 halberd); `SotePlan.of(team)` is the sim's default team (cli/sote.mjs --full); running the maze isn't
  ported;
- `bloat/VSoteScript.kt`: `::sote [1-5] [median | top10 | top50 | ...]` and `SOTE_SELFTEST=5/s1,2/s3,...`;
  `SOTE_TRACE=1` logs every tick (her HP, each bot's tile, HP and attack) for replays.

**Parity:** the same seed gives the same raid as verzikSim, log line for line:
`tools/sote-parity/check.sh <verzikSim dir> <team> <raids>` (after `./gradlew -q :content:raids:tob-bloat:classes`).
Change a mechanic on both sides (verzikSim is in `degen19/verzikSim.JS`; use a branch there).

**Blert (top 50 by scale, regular, 2026-11-27):** medians solo 468, duo 220, trio 159, 4s 144, 5s 135 ticks; every top
team skips both mazes. The sim's damage per scythe swing matches ordinary Blert raids (solo 36.9 vs 37.2, duo 40.6 vs
41.0, trio 41.7 vs 40.8); the top 50 are each team's luckiest runs and sit in the sim's fastest ~10%, so `top50` picks
raids from that end rather than making the bots hit harder.

**Next:** the bots' look per scale (kits still show void for ranged; the sim's numbers are Masori), running the maze,
and where the plan itself can beat the default (the sim's solver finds solos ~2% faster).
