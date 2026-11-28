# Bloat

**Files:**
- `solo/BloatSolo.kt`: the room in the raid;
- `bloat/BloatRoom.kt`, `bloat/BloatRules.kt`: walk, turns, downs, stomp, hands, flies;
- `bloat/VBloatScript.kt`: the practice room, `BLOAT_SELFTEST`.

**Data:** many real raids' Bloat events in `tools/blert/data/raids/11_*`. The `bloat*.py` scripts are earlier fits of
his walk and downs.

**There's no bot crew yet:** the bots don't play Bloat. Start by checking the room against Blert: walk path and speed by
HP, down timings, hand and fly patterns. Then build a crew in the style of the Maiden and Nylo ones (`BotCrew`).

**Sounds:** the flies' attack sounds and the death sound were added recently. Check them against a real recording.
