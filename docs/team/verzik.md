# Verzik

**Files:**
- `solo/VerzikSolo.kt`: the room (P1 to P3 and the transitions);
- `bloat/verzik/*`: the live room;
- `bloat/VVerzikScript.kt`: the practice commands;
- `resources/verzik/verzik5.json.gz`: 5s plans exported from verzikSim (`solve5.mjs`, `vexport.mjs`).

**verzikSim.JS** (`degen19/verzikSim.JS`) is the Verzik sim: Monte Carlo, 2–5 man, an optimizer and solver, and the
chart template. Read its `HANDOFF.md`, `README.md` and `CONVENTIONS.md`.
- Its main branch is the live site: push only when the owner says "push".
- Verzik 2–5 man behaviour stays as it is unless the owner asks.
- "Success" means killed before the fail round.

**Rules from the owner:** P2 reds (the R-numbered ones) are Verzik terms only. Don't use them for other rooms.

**Next:** keep the server's Verzik and verzikSim in step (same seed, same raid), then the bots' play from the sim's
plans.
