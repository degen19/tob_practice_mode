# ToB practice mode — the agent team

Read this first, then your room's brief in this folder. Every agent works from this page, and the Admin keeps it current.

## The goal

A Theatre of Blood practice server that plays like OSRS, 1:1. The owner, or a team, raids with bots filling the empty
seats. The bots play like the fastest real teams in Blert, role by role: same gear, same ticks, same specs, same
positions. A bot behaving in a way no real team would is a bug, even when it's fast.

Ground truth, in this order:
1. **The owner's word.** It overrides everything below.
2. **Blert:** recordings of real raids (`tools/blert`).
3. **The owner's recordings,** and the existing sims (verzikSim.JS, `tools/nylo-sim`).

## Repos

- `degen19/tob_practice_mode`: the server (OpenRune, Kotlin). ToB lives in `content/raids/tob-bloat`.
- `degen19/verzikSim.JS`: the browser and Node Monte Carlo sim for Verzik and Sotetseg (Xarpus local only). Several
  server rooms are ports of its engines (see "Tie-ins").

## How we work: branches, one Admin

- Each room has its own agent and branch, named `room/<room>` (for example `room/maiden`), in `tob_practice_mode`. If
  you change verzikSim.JS, use a branch of the same name there.
- **You never deploy.** Only the Admin puts anything on the owner's PC: no `.data/restart.flag`, no copying files to
  the PC. Two agents restarting the server collide, and the owner may be in it watching.
- Push only tested changes. Each commit message says what changed and what it did, with numbers: the room's time or
  tick, failures (leaks, stalls, deaths), and before vs after from the same test.
- Stay in your room's files. Shared files (`SoloRaidScript.kt`, `SoloRoom.kt`, `NyloLoadout.kt`, `RaidBots.kt`,
  `BotCrew.kt`, the pack's `tob_npcs.toml`) are allowed, but say so in the commit message so the Admin can watch for
  conflicts. Pull main often: `git fetch origin && git rebase origin/main`.
- The Admin merges `room/*` into main after a combined test, deploys to the PC, and gives the owner a short summary per
  branch.

## Standing rules (from the owner)

- Builds only ever get better. Never ship something slower or less correct than what's live.
- Keep whatever process is better. Don't leave old and new paths side by side behind switches.
- Make only the change asked for. No drive-by edits.
- **Nobody ever loses a tick.** Real teams don't. Fix the cause. Any catch-up logic is only a safety net.
- Never type the owner's password anywhere, and never repeat it.
- No changelog or patch notes until the owner asks.
- `AGENTS.md` at the repo root is the code style. Highlights:
  - gamevals spelled out in full;
  - minimal comments;
  - `SCREAMING_SNAKE` constants;
  - lookup data in the cache;
  - varbits over attrs.
- Commit messages end with:
  ```
  Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
  Claude-Session: <your own session link>
  ```

## Testing without the owner

- **Build:** `./gradlew install` once, then `./gradlew -q :server:app:classes`.
  - If you touched a `-pack` module, a `gamevals.toml` or CS2, also run `./gradlew -q :or-cache:buildCache`.
  - PostgreSQL has to be running: `service postgresql start`.
- **Bot batches** (Maiden and Nylo have bot crews): `tools/bot-batch`.
  - It runs n bot teams in one server at 60 ms ticks and prints one result line per team.
  - Set `TOB_TRIO_TRACE=1` for per-tick bot traces.
  - Batches are noisy: 12 rooms run together and the machine lags. Compare builds on at least 24 rooms, and on the same
    machine.
- **Room self-tests** (bots play the sim's plan live, no client needed):
  - `MAIDEN_SELFTEST`, `BLOAT_SELFTEST`, `NYLO_SELFTEST`, `SOTE_SELFTEST`, `XARPUS_SELFTEST`;
  - `TOB_SELFTEST=maiden,bloat,...` walks a solo bot room by room.
  - See the `V*Script.kt` files in `content/raids/tob-bloat/.../bloat/`.
- **Other useful environment variables:**
  - `TOB_HIT_TRACE`, `TOB_NYLO_POLICY` (a Nylo weights file);
  - `TOB_TRIO_ROOM=maiden|nylo`, `TOB_TRIO_SIZE=4`, `TOB_TRIO_PARAMS`.
- **Blert data:** `tools/blert/README.md`. It covers the bundled top-trio Maiden and Nylo rooms, and how to fetch any
  room's events from the Blert API.
- The owner watches in a real client. If something needs eyes, ask the Admin to deploy it.

## Tie-ins to verzikSim.JS

- **Sotetseg, Xarpus and Verzik:** the server's `bloat/sote/SoteEngine.kt`, `bloat/xarpus/*` and `bloat/verzik/*` are
  ports of verzikSim's engines, so the same seed gives the same raid.
- **Verzik plans:** `src/main/resources/verzik/verzik5.json.gz` holds them, exported by `solve5.mjs` / `vexport.mjs`.
- **Change both sides together.** A mechanic fixed on the server gets fixed in verzikSim too, and the other way round.
  The Admin checks that the two still match.
- **verzikSim's own rules** (its `HANDOFF.md`, `CONVENTIONS.md`):
  - nothing goes live on GitHub Pages without the owner saying "push";
  - Xarpus stays local;
  - the `.xlsx` template is edited only by patching its XML.

## The shared to-do list

- **Nylo:** bots to a 2:55.2 median boss spawn in trios (from about 2:57.6 now). Specs are in the sim and in
  `NyloCrew`, but not live yet: the mage's Tome spec (Zorya's flow) once a room on a Lightbearer, the melee's one
  Dinh's, and Death Charge for the ranger and melee. Then the Nylo boss in the sim, with spec limits, and a PPO learner
  kept close to Blert's human play.
- **Maiden:**
  - **Live:** the team's tick (no lost ticks), freezers tbowing from range, the 30s missing-crab handling, preloads on
    clumps.
  - **Open:** finish removing every cause of a lost tick, and the mfrz's tick in 4s.
- **Sounds:** record a real raid with the dev-tools plugin (`get_sound_history`) to fix sound timings. Spec sounds are
  still missing for ZCB, Elder maul, Ralos and Dinh's.
- **Later:**
  - spectating from a bot's view;
  - 5-man teams;
  - mage on Lunars with spellbook swap as a config option for the grouping and play-along modes.
- **Before going live:**
  - delete `.data/dev-any-password.flag` on the owner's PC (Admin);
  - remove scratch trace code.
