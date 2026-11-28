# Sotetseg

**Files:**
- `solo/SoteSolo.kt`: the room in the raid;
- `bloat/sote/*`: `SoteEngine` is a line-for-line port of verzikSim's `engine/sote.js` (5s: prefires, horn, mauls,
  ZCBs, orbs, mazes on a phoenix necklace, thralls, P3 halberd);
- `bloat/VSoteScript.kt`: `::sote` and `SOTE_SELFTEST`.

**Parity:** the same seed gives the same raid as verzikSim. Change a mechanic on both sides (verzikSim is in
`degen19/verzikSim.JS`; use a branch there), and run its `node cli/sote.mjs 4 300` check.

**Next:** check timings against Blert (stage 13), then party sizes beyond 5s (shark skips, redemption skips, running
the maze, duos and solos aren't there).
