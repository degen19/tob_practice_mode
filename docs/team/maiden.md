# Maiden

**Files:**
- `solo/MaidenSolo.kt`: the room;
- `solo/MaidenCrew.kt`: the bot crew, trios and 4s;
- `bloat/maiden/*`: the sim engine and plans the crew follows;
- `bloat/VMaidenScript.kt`: `::maiden` and the self-test.

**Test:** `tools/bot-batch` with `maiden` (12 trios at a time; `TOB_TRIO_SIZE=4` for 4s). Read traced logs with
`maiden_check.py`. Blert's top 50 trio Maiden rooms are in `tools/blert/blert-top.tgz`.

## Rules the owner has stated

- **Nobody loses a tick:**
  - the ranger and melee swing on the same tick;
  - the mage's tbow and barrages go 2 ticks after theirs (Blert: 216 of 221);
  - fix the causes;
  - the catch-up hits (4-tick claws, 3-tick or 2-tick blowpipe) are only a safety net;
  - none of this matters after the 30s proc.
- **Freezers tbow from their usual range** (mage and mfrz), never from up close: close, the barrage projectile lands
  too soon and breaks freezes.
- **30s with a 1 or 2 missing:** the mage loses a tick noticing, barrages the clump, then ZCBs.
- **Preloads go on a clump,** not a lone floating crab.
- **No melee rushing an unfreezeable crab on spawn.** It waits until the crab is close to Maiden, for the cleave hit,
  unless that would push back a necessary Dinh's.
- **Freezers never stand in blood or path,** and never lose ticks to blood.

## Open

- **Remaining lost ticks:** a DPS waiting on its Dinh's crab away from Maiden, and blood dodges on the swing tick.
  Swinging first and stepping off after cost leaks, so it was reverted. The real fix is pre-dodging to tiles the blood
  won't hit.
- **The mfrz's tick in 4s:** check it against Blert's top 4s.
- **The mage walking in** for a freeze that's then cancelled: the root cause of most close tbows.
