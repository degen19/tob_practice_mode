# Xarpus

**Files:**
- `solo/XarpusSolo.kt`: the room;
- `bloat/xarpus/*`: a port of verzikSim's Xarpus engine;
- `bloat/VXarpusScript.kt`: `XARPUS_SELFTEST`.

**verzikSim's Xarpus** (`engine/xarpus*.js`) is on its main branch. Pushing to verzikSim main still needs the owner
to say "push".

**Next:** check exhumed timings (every 12 ticks from the first instance tick 1 at or after tick 8, 7 of them), the
poison spits and the P3 turns against Blert (stage 14). Then the crew.

**From the Admin (1c5fb3d, live):** P3 lost ticks are gone, but each bot now takes more acid than Blert's teams:
trio 87, 4s 77, 5s 62 a bot (Blert 49 / 42 / 38). The Admin's batch: trio bots took 53-114 each. Real teams don't stand
in that much acid, so bring it down without losing the ticks back.

**From the Admin (3ec68f3, held):** in the Admin's interleaved A/B (trio, 24 rooms a side, same machine) it was slower
and didn't bring the damage down: main 253.6 mean, median 253.5, 66.9 a bot; 3ec68f3 257.8 mean, median 258, 65.9 a
bot (your batch: 68 -> 50). Find what costs the 4 ticks in trios, and why the damage didn't move here, then push again.
