# Verzik

**Files:**
- `solo/VerzikSolo.kt`: the room (P1 to P3 and the transitions);
- `bloat/verzik/*`: the live room;
- `bloat/VVerzikScript.kt`: the practice commands;
- `resources/verzik/verzik5.json.gz`: 5s plans exported from verzikSim (`solve5.mjs`, `vexport.mjs`);
- `resources/verzik/blert.json.gz`: Blert's 50 fastest rooms per scale (2-5), replayed by `::verzik blert <scale> [rank]`
  (`bloat/verzik/VerzikReplayRoom.kt`; exported by `tools/blert/verzik.py export`);
- `bloat/verzik/VerzikLooks.kt`: how her room looks and sounds, shared by the replay and the sim's raids;
  `bloat/BlertLooks.kt`: a recorded attack shown as the server's combat shows it.

**verzikSim.JS** (`degen19/verzikSim.JS`) is the Verzik sim: Monte Carlo, 2–5 man, an optimizer and solver, and the
chart template. Read its `HANDOFF.md`, `README.md` and `CONVENTIONS.md`.
- Its main branch is the live site: push only when the owner says "push".
- Verzik 2–5 man behaviour stays as it is unless the owner asks.
- "Success" means killed before the fail round.

**Rules from the owner:**
- P2 reds (the R-numbered ones) are Verzik terms only. Don't use them for other rooms.
- HP: verzikSim's values are right (P2/P3 3500 / 3062 / 2625 by scale). Blert's are provably wrong; the replay puts
  Blert's hitpoints on our scale.
- "Success" stays a kill within the reds' fail round, counted as X downs per scale (set in the lobby). Raids that fail
  will carry on to the kill, here and in verzikSim.
- Spec procedures vary by team and get built in the lobby.
- P2 crab popping: the sim assumes a set Boak pattern per side; real teams end it early or pop the purple sooner
  depending on the crabs' spawns and aggro. Model it from Blert, then bring it back to the sim.
- Animations and sounds have to feel 1:1.

**What Blert shows (`tools/blert`, top 50 per scale):**
- Her P1 blast and her P2 spark both hurt 2 ticks after her attack (537 of 566, 320 of 345).
- Her death: Blert seldom has the tick her hitpoints reach 0; P1 dies 4 ticks before her climb-down form, P2 and P3 2
  ticks before the next form (every raid).
- 911 of 923 P2/P3 nylocas are never attacked: they turn (detonate animation, 2 ticks) and pop.
- The P2 kill comes within 44 ticks of the reds in all top 4s and 5s, 21 of 50 trios, no duos.

**Bugs from the owner's first look at `::verzik blert` (04d9848), and their state:**
1. P1 tile: she was the size-1 `verzik_initial_quickstart` transmogged into P1, so her model sat west of the throne.
   Now each form is a new NPC of its own size; the start form is `verzik_initial` (size 5) at Blert's tile. Checked:
   size 5 at 3166,4323 on the 5x4 throne (3166,4324). Needs eyes.
2. P1 orb: lands 2 ticks after her attack (Blert), leaving 20 client cycles in on a steeper arc. Checked: every orb
   lands on the tick the recorded player hitpoints drop. Launch frame and arc are a guess until a real-game capture
   (`get_projectile_history`).
3. P1 death / transition: her death animation now plays when she dies (4 ticks before the climb-down form), and every
   form comes in as a new NPC on Blert's tile. Checked: NPC drift 0 on 8 raids, her 3 deaths each raid.
4. Dawnbringer on the floor: from each Blert drop to its pick-up, on Blert's tile. Checked against the recorded drops.
5. Hitsplats on her: each hit landing that tick its own splat (weapon hit delays from verzikSim; a scythe's 4:2:1, a
   Dawnbringer spec's recorded damage), others' tint. Checked: splats add up to each tick's recorded damage.
6. Hitsplats on players: from the recorded hitpoints (Blert has them for the players who recorded).
7. Reds' spawn: `elemental_spawn` on their first tick (was a tick late); the purple climbs out (`tob_spider_tank_spawn`).
8. Nylocas deaths: turn for 2 ticks (detonate animation), then pop on Blert's tick; killed ones pivot, then pop.
9. P2 shield effect: not done; which spotanim the shield's cloud is needs a real-game capture (`get_effect_history`).
10. Webs: the solo room's volleys while she spins (Blert doesn't record webs), web NPC and loc for 30 ticks.
11. Under her during webs: her tiles block players for the spin. Checked: 49 tiles flagged.
- The owner has a few more to send.

**Next:** the owner's remaining bugs; a real-game capture for her projectiles and effects; the sim's raids at every
scale in the server with the same looks; P2 crab popping (Boak and its early ends) from Blert; the PPO learner for the
bots' play, kept close to Blert.
