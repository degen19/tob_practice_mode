# Nylocas (waves and boss)

**Files:**
- `solo/NyloSolo.kt`: the room;
- `solo/NyloCrew.kt`: the bots;
- `solo/NyloPolicy.kt` and `resources/tob/nylo_policy.tsv`: the learned weights;
- `bloat/nylo/*`: the older sim port.

**Sim and trainer:** `tools/nylo-sim` (Node, see its README). `start.json` is the latest weights, specs included.

**Test:** `tools/bot-batch` with `nylo`, pointing `TOB_NYLO_POLICY` at an exported `.tsv`. Run at least 120 rooms
before claiming a gain: the server's mean moves by about ±0.5 ticks between 60-room batches.

## State

- **Live:** learned weights, a mean boss spawn of 2:57.5 (tick 295.8) in trios.
  - 240 rooms: 2:50 1, 2:52 11, 2:55 64, 2:57 99, 3:00 55, slower 10.
  - The top Blert trios mostly hit 2:52.8.
- **Target:** a 2:55.2 median (tick 292) in trios. Report the 2:50 / 2:52 / 2:55 / 2:57 / 3:00 / slower counts and the
  median for every batch or generation.
- **Built but not live** (the live weights don't know them; retrain first):
  - the mage's Tome spec (Zorya's flow) once a room, on a Lightbearer;
  - the melee's Dinh's once a room;
  - the ranger and melee on Arceuus recasting Death Charge (15% spec back on a kill).
- **Specs:** the mage gets 1 Tome spec per room's waves, and the melee 1 Dinh's. A second Dinh's only once the boss is
  in the sim and it beats a ZCB on the boss.
- **Cap (confirmed from Blert):**
  - a killed small counts 2 ticks after its killing hitsplat;
  - a big counts 1 until its splits appear 6 ticks later.
  - The room ends on the same rule, and the boss comes 16–19 ticks after that.

## Next

- **The gap to humans** is in waves 28–31: humans kill the fresh smalls 9–14 ticks younger.
- **The Nylo boss** goes into the sim.
- **PPO:** a learner started from Blert players (one part per role), with a penalty for drifting from them, so it stays
  human.

**From the Admin (515271e, held):** the PC doesn't run main's NyloCrew. Live is still `NyloCrew.kt`, `NyloPolicy.kt` and
`nylo_policy.tsv` as of 05aa401 (snapE, no specs), plus the lobby's one-line seat check on its member loop (`|| bot !in bots.all`, deploy76). Compare against that, not main. Admin's interleaved A/B, 48 trio
rooms a side, same machine: live mean 296.00, median 296, 17 at 2:55.2 or better, 34 at 2:57.6 or better; 515271e
(main's spec crew + the new weights) 297.33, 296, 9, 28. The spec build has to beat 05aa401 before it goes live.
