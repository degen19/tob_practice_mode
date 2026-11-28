# Lobby and grouping

The way into a raid: a loading lobby where a player, or a group of players, forms a party, picks seats and settings,
and goes in with bots filling the empty seats.

## What exists now (build on it, don't fork it)

- **`solo/SoloRaidScript.kt`:** `::tob <room> | stop | where | mode | start | watch | auto` and `::watch`.
  - Each run is a `SoloRoom` with an owner plus `helpers` (bots or people).
  - `startTrio(...)` spawns a bot crew: `spawnCrewBot`, `refit`, `kit`, `potUp`, `walkIn`.
  - `teamRoles[room]` holds the seat roles.
- **`solo/NyloLoadout.kt`:**
  - `NyloRole` (`Mage`, `Ranger`, `Melee`, plus `Freezer` for the 4s mfrz);
  - `NyloRole.TRIO` / `QUAD`;
  - each role's gear sets for Nylo (`sets`) and Maiden (`maidenSets`).
- **`solo/BotCrew.kt`:** the interface each room's crew implements. `MaidenCrew` and `NyloCrew` are the two crews today.
- **`api/player/.../PracticeSettings.kt`:** practice toggles (no deaths, infinite supplies, Nylo labels...). These are
  global today.
- **Supplies:** `bloat/SupplyChest.kt`, `bloat/SupplyCatalog.kt`, and the `tob_supplies` interfaces in the pack.

## What to build

1. **Lobby:**
   - a party leader, invites, and joining;
   - a seat per role, each a person or a bot;
   - per-group settings;
   - start, which walks the party into Maiden on the right instance tick (see `walkIn`).
2. **The group config** each room's crew reads. Today's code has no such object; settle its shape with the Admin first,
   then build it here:
   - **size:** solo, duo, trio, 4s or 5s;
   - **seats:** role, person or bot, and the bot's gear sets and spellbook;
   - **per-role options** the owner has named, for example the mage on Lunars with spellbook swap (SBS barrages;
     Death Charge then on the mage too), versus on Ancients without Death Charge as now;
   - **play-along modes**, and the `PracticeSettings` toggles made per group rather than global.
3. **Wiring:**
   - bots spawn per seat with that seat's kit;
   - `crewFor` hands each room's crew the group config instead of today's fixed trio and 4s lists.
   - Rooms without a crew yet just get the people.

## Rules

- **Interfaces look like Jagex's:** see AGENTS.md "Interface design" (steel border, standard components, cache fonts).
  Find the closest shipped interface (osrs-dumps) and copy its structure.
- **Varbits/varps over attrs** for any per-player or per-group state.
- **The room agents own their crews.** You own the config and the plumbing. If a crew needs to change to read the
  config, keep that change minimal and say so in the commit.

## From the Admin

- **b778219 is held, not deployed.** With the lobby up, the six wandering bots (character ids -1 to -6) are saved like
  players: `AccountSavingService` writes their varps, and every save fails on the `character_varps` and
  `activity_logs` foreign keys (24 `BatchUpdateException`s in a 12-room Maiden batch, none on main). Keep the lobby
  bots out of account saving and the activity log, the way the raid crews' bots are, then push again. Everything
  else checked out: the cache builds, "tob lobby at 10304,64: 6 bosses, 6 bots", Maiden trio 130.2 on 12 rooms.
