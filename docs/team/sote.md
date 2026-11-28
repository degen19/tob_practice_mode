# Sotetseg

**Files:**
- `solo/SoteSolo.kt`: the room in the raid;
- `bloat/sote/*`: `SoteEngine` is a line-for-line port of verzikSim's `engine/sote.js` runSote, every scale (prefires,
  horn, mauls, ZCBs, orbs, the death ball, maze stalls on a phoenix necklace, shark and redemption skips, Lightbearer,
  thralls, P3 halberd); `SotePlan.of(team)` is the sim's default team (cli/sote.mjs --full); running the maze isn't
  ported;
- `bloat/sote/SoteMaze.kt`: the sim's maze (sotemaze.js) for running it;
- `bloat/VSoteScript.kt`: `::sote [1-5] [pneck | shark | noneck | redemption | run] [median | top10 | top50 | ...]` and
  `SOTE_SELFTEST=5/s1,2/run/s3,...` (team/way/seed);
  `SOTE_TRACE=1` logs every tick (her HP, each bot's tile, HP and attack) for replays.

**Parity:** the same seed gives the same raid as verzikSim, log line for line:
`tools/sote-parity/check.sh <verzikSim dir> <team> <raids>` (after `./gradlew -q :content:raids:tob-bloat:classes`).
Change a mechanic on both sides (verzikSim is in `degen19/verzikSim.JS`; use a branch there).

**Blert (top 50 by scale, regular, 2026-11-27):** medians solo 468, duo 220, trio 159, 4s 144, 5s 135 ticks; every top
team skips both mazes. The sim's damage per scythe swing matches ordinary Blert raids (solo 36.9 vs 37.2, duo 40.6 vs
41.0, trio 41.7 vs 40.8); the top 50 are each team's luckiest runs and sit in the sim's fastest ~10%, so `top50` picks
raids from that end rather than making the bots hit harder.

**Live room (Blert):** every scale flinches - prefire t5, P1 maul t10, scythes on her attack ticks, each bot a tile back
out of her reach between swings and in on the swing tick; the west bot drops a shark at the top of column 1 on t12-13;
at a maze the crossers are teleported to column 1 south of the maze on proc +3, run up it two tiles a tick from the
tick before the stall, cast NPC Contact on row 3 (a shark skipper runs up column 11 if others stall), and off the top
swing first from the tile beside her nearest them. Running: they follow the maze's best route.

**Crossings on Blert (top 50):** duos 61 on a necklace, 20 shark skips, 1 run; trios 142 / 16 / 15; ordinary trios
mostly run (164 of 174). The sim's shark skip kills a duo bot in 7% of raids (one skip) and 64% (two, `noneck`).

**Open question for the owner:** on Blert 10-18% of her attacks are melees even on flinching teams (adjacent players:
~20% when they swung on her tick, ~50% otherwise); the sim only melees a player who swung the tick before (~1%), so
its bots cast more vengs than Blert's (duo 8.5 vs 4.6 a raid, 5s 10.9 vs 6.7).

**Next:** the bots' look per scale (kits still show void for ranged; the sim's numbers are Masori), running the maze,
and where the plan itself can beat the default (the sim's solver finds solos ~2% faster).
