package org.rsmod.content.skills.magic.lunar

import dev.openrune.ServerCacheManager
import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.rscm.RSCMType
import dev.openrune.types.ItemServerType
import jakarta.inject.Inject
import org.rsmod.api.player.output.UpdateInventory.resendSlot
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.protect.ProtectedAccessLauncher
import org.rsmod.api.player.protect.clearPendingAction
import org.rsmod.api.player.stat.baseCookingLvl
import org.rsmod.api.player.stat.baseCraftingLvl
import org.rsmod.api.player.ui.IfOverlayButtonT
import org.rsmod.api.player.vars.boolVarBit
import org.rsmod.api.random.GameRandom
import org.rsmod.api.repo.obj.ObjRepository
import org.rsmod.api.script.onEvent
import org.rsmod.api.script.onIfOverlayButton
import org.rsmod.api.script.onOpHeld1
import org.rsmod.api.script.onPlayerQueue
import org.rsmod.api.script.onPlayerQueueWithArgs
import org.rsmod.events.EventBus
import org.rsmod.game.entity.Player
import org.rsmod.game.inv.isType
import org.rsmod.plugin.scripts.PluginScript
import org.rsmod.plugin.scripts.ScriptContext

private val Player.hardFremennikDiary by boolVarBit("varbit.fremennik_diary_hard_complete")

/**
 * The Lunar spells that work on items in the inventory: Bake Pie, Humidify, Hunter Kit, Spin
 * Flax, Superglass Make, Tan Leather, String Jewellery, Recharge Dragonstone and Plank Make.
 */
class LunarItemSpells
@Inject
constructor(
    private val caster: LunarCaster,
    private val eventBus: EventBus,
    private val protectedAccess: ProtectedAccessLauncher,
    private val objRepo: ObjRepository,
    private val random: GameRandom,
) : PluginScript() {
    override fun ScriptContext.startup() {
        onIfOverlayButton("component.magic_spellbook:bake_pie") { bakePie() }
        onPlayerQueue(BAKE_PIE_QUEUE) { bakePie() }
        onIfOverlayButton("component.magic_spellbook:humidify") { humidify() }
        onIfOverlayButton("component.magic_spellbook:hunter_kit") { hunterKit() }
        onOpHeld1(HUNTER_KIT) { openHunterKit(it.slot) }
        onIfOverlayButton("component.magic_spellbook:spin_flax") { spinFlax() }
        onIfOverlayButton("component.magic_spellbook:superglass") { superglassMake() }
        onIfOverlayButton("component.magic_spellbook:tan_leather") { tanLeather() }
        onIfOverlayButton("component.magic_spellbook:string_jewel") { stringJewellery() }
        onPlayerQueue(STRING_QUEUE) { stringJewellery() }
        onIfOverlayButton("component.magic_spellbook:recharge_dragonstone") {
            rechargeDragonstone()
        }
        val plankMake =
            EventBus.composeLongKey(
                "component.magic_spellbook:plank_make".asComponent(),
                "component.inventory:items".asComponent(),
            )
        onEvent<IfOverlayButtonT>(plankMake) { opPlankMake(this) }
        onPlayerQueueWithArgs<Int>(PLANK_QUEUE) { plankMake(it.args) }
    }

    /**
     * Bake Pie (65): bakes one uncooked pie per cast, without burning, for full Cooking
     * experience, and keeps casting on the next pie every 5 ticks until none are left.
     */
    private fun ProtectedAccess.bakePie() {
        val slot = inv.indexOfFirst { obj -> PIES.any { obj.isType(it.raw) } }
        if (slot == -1) {
            mes("You have no pies which you have the level to cook.")
            return
        }
        val pie = PIES.first { inv[slot].isType(it.raw) }
        if (player.baseCookingLvl < pie.level) {
            mes("You need a Cooking level of ${pie.level} to cook this.")
            return
        }
        val spell = caster.spell("obj.65_bake_pie") ?: return
        if (!caster.cast(player, spell)) {
            return
        }
        anim(SUMMON_ANIM)
        spotanim("spotanim.quest_lunar_bakepie_spotanim", height = 92)
        invReplaceSlot(inv, slot, 1, pie.cooked.objType())
        statAdvance("stat.cooking", pie.xp)
        mes("You bake the pie.")
        if (inv.any { obj -> PIES.any { obj.isType(it.raw) } }) {
            weakQueue(BAKE_PIE_QUEUE, REPEAT_TICKS)
        }
    }

    /**
     * Humidify (68): fills every empty water container in the inventory (vials, bowls, buckets,
     * jugs, cups, fishbowls, waterskins and watering cans) in one cast.
     */
    private fun ProtectedAccess.humidify() {
        val slots = inv.indices.filter { i -> HUMIDIFY.keys.any { inv[i].isType(it) } }
        if (slots.isEmpty()) {
            mes("You have nothing in your inventory that this spell can humidify.")
            return
        }
        val spell = caster.spell("obj.68_humidify") ?: return
        if (!caster.cast(player, spell)) {
            return
        }
        anim("seq.dream_player_humidify_spell")
        spotanim("spotanim.dream_humidify_spell_spotanim", height = 92)
        for (slot in slots) {
            val empty = HUMIDIFY.keys.first { inv[slot].isType(it) }
            invReplaceSlot(inv, slot, 1, HUMIDIFY.getValue(empty).objType())
        }
    }

    /**
     * Hunter Kit (71): puts a hunter kit in the inventory, which opens into the basic Hunter
     * tools. It needs a free inventory space.
     */
    private fun ProtectedAccess.hunterKit() {
        if (inv.isFull()) {
            mes("You don't have enough inventory space to cast this spell.")
            return
        }
        val spell = caster.spell("obj.71_hunter_kit") ?: return
        if (!caster.cast(player, spell)) {
            return
        }
        anim("seq.dream_player_hunter_box_spell")
        spotanim("spotanim.dream_hunter_spell_spotanim", height = 92)
        invAdd(inv, HUNTER_KIT)
    }

    /** Opening the hunter kit swaps it for its seven Hunter tools. */
    private fun ProtectedAccess.openHunterKit(slot: Int) {
        if (inv.freeSpace() < HUNTER_KIT_TOOLS.size - 1) {
            mes("You need ${HUNTER_KIT_TOOLS.size - 1} free inventory spaces to open the hunter kit.")
            return
        }
        invDel(inv, HUNTER_KIT, 1, slot)
        for (tool in HUNTER_KIT_TOOLS) {
            invAdd(inv, tool)
        }
    }

    /** Spin Flax (76): spins up to five flax into bow strings at once (15 Crafting xp each). */
    private fun ProtectedAccess.spinFlax() {
        val count = minOf(inv.count(FLAX), MAX_PER_CAST)
        if (count == 0) {
            mes("You need some flax to cast this spell.")
            return
        }
        if (player.baseCraftingLvl < SPIN_FLAX_LEVEL) {
            mes("You need a Crafting level of $SPIN_FLAX_LEVEL to spin flax.")
            return
        }
        val spell = caster.spell("obj.76_spin_flax") ?: return
        if (!caster.cast(player, spell)) {
            return
        }
        anim(SUMMON_ANIM)
        spotanim("spotanim.quest_lunar_magic_flax_strim", height = 92)
        repeat(count) {
            val slot = inv.indexOfFirst { it.isType(FLAX) }
            invReplaceSlot(inv, slot, 1, BOW_STRING.objType())
        }
        statAdvance("stat.crafting", SPIN_FLAX_XP * count)
    }

    /**
     * Superglass Make (77): turns buckets of sand plus soda ash, seaweed or swamp weed (one each)
     * or giant seaweed (one per six sand) into molten glass at once, with no furnace. Each bucket
     * of sand gives a glass, with a 30% chance of a second (45% with giant seaweed); glass that
     * doesn't fit is dropped. The buckets are used up. 10 Crafting xp per sand.
     */
    private fun ProtectedAccess.superglassMake() {
        val sand = inv.count(SAND)
        val giant = minOf(inv.count(GIANT_SEAWEED), sand / GIANT_SEAWEED_SAND)
        val sandAfterGiant = sand - giant * GIANT_SEAWEED_SAND
        val regular = minOf(SUPERGLASS_SECONDARIES.sumOf { inv.count(it) }, sandAfterGiant)
        if (giant == 0 && regular == 0) {
            mes("You need some sand and soda ash, seaweed, swamp weed or giant seaweed to cast this spell.")
            return
        }
        val spell = caster.spell("obj.77_superglass") ?: return
        if (!caster.cast(player, spell)) {
            return
        }
        anim(SUMMON_ANIM)
        spotanim("spotanim.quest_lunar_magic_superglass", height = 92)
        val usedSand = giant * GIANT_SEAWEED_SAND + regular
        invDel(inv, SAND, usedSand)
        if (giant > 0) {
            invDel(inv, GIANT_SEAWEED, giant)
        }
        var secondaries = regular
        for (secondary in SUPERGLASS_SECONDARIES) {
            val take = minOf(secondaries, inv.count(secondary))
            if (take > 0) {
                invDel(inv, secondary, take)
                secondaries -= take
            }
        }
        var glass = 0
        repeat(giant * GIANT_SEAWEED_SAND) { glass += if (random.of(100) < 45) 2 else 1 }
        repeat(regular) { glass += if (random.of(100) < 30) 2 else 1 }
        repeat(glass) { invAddOrDrop(objRepo, MOLTEN_GLASS) }
        statAdvance("stat.crafting", SUPERGLASS_XP * usedSand)
    }

    /**
     * Tan Leather (78): tans up to five hides at once with no tanner fee: cowhide into leather,
     * snake hides into snakeskin and dragonhides into dragon leather. Needs the Hard Fremennik
     * Diary.
     */
    private fun ProtectedAccess.tanLeather() {
        if (!player.hardFremennikDiary) {
            mes("You need to complete the Hard Fremennik Diary to cast this spell.")
            return
        }
        val slots = inv.indices.filter { i -> HIDES.keys.any { inv[i].isType(it) } }.take(MAX_PER_CAST)
        if (slots.isEmpty()) {
            mes("You need some hides to cast this spell.")
            return
        }
        val spell = caster.spell("obj.leather") ?: return
        if (!caster.cast(player, spell)) {
            return
        }
        anim(SUMMON_ANIM)
        spotanim("spotanim.quest_lunar_spellbook_bake_pie_spot_anim", height = 92)
        for (slot in slots) {
            val hide = HIDES.keys.first { inv[slot].isType(it) }
            invReplaceSlot(inv, slot, 1, HIDES.getValue(hide).objType())
        }
    }

    /**
     * String Jewellery (80): strings one unstrung amulet per cast (4 Crafting xp) and keeps
     * casting on the next one every 5 ticks until none are left.
     */
    private fun ProtectedAccess.stringJewellery() {
        val slot = inv.indexOfFirst { obj -> AMULETS.keys.any { obj.isType(it) } }
        if (slot == -1) {
            mes("You don't have any amulets to string.")
            return
        }
        val spell = caster.spell("obj.80_string_jewelry") ?: return
        if (!caster.cast(player, spell)) {
            return
        }
        val unstrung = AMULETS.keys.first { inv[slot].isType(it) }
        anim("seq.lunar_human_magic_summon1")
        spotanim("spotanim.quest_lunar_spellbook_string_jewelery_spot_anim", height = 92)
        invReplaceSlot(inv, slot, 1, AMULETS.getValue(unstrung).objType())
        statAdvance("stat.crafting", STRING_XP)
        if (inv.any { obj -> AMULETS.keys.any { obj.isType(it) } }) {
            weakQueue(STRING_QUEUE, REPEAT_TICKS)
        }
    }

    /**
     * Recharge Dragonstone (89): recharges every amulet of glory, skills necklace and combat
     * bracelet in the inventory to four charges (not the ring of wealth). Needs the Hard
     * Fremennik Diary.
     */
    private fun ProtectedAccess.rechargeDragonstone() {
        if (!player.hardFremennikDiary) {
            mes("You need to complete the Hard Fremennik Diary to cast this spell.")
            return
        }
        val slots = inv.indices.filter { i -> RECHARGE.keys.any { inv[i].isType(it) } }
        if (slots.isEmpty()) {
            mes("You don't have any dragonstone jewellery that needs recharging.")
            return
        }
        val spell = caster.spell("obj.caveorb4dot") ?: return
        if (!caster.cast(player, spell)) {
            return
        }
        anim(SUMMON_ANIM)
        spotanim("spotanim.quest_lunar_spellbook_string_jewelery_spot_anim", height = 92)
        for (slot in slots) {
            val worn = RECHARGE.keys.first { inv[slot].isType(it) }
            invReplaceSlot(inv, slot, 1, RECHARGE.getValue(worn).objType())
        }
        mes("You recharge your dragonstone jewellery.")
    }

    private fun opPlankMake(event: IfOverlayButtonT) {
        val player = event.player
        if (player.isDelayed || player.isAccessProtected) {
            resendSlot(player.inv, 0)
            return
        }
        player.clearPendingAction(eventBus)
        protectedAccess.launch(player) { plankMake(event.targetSlot) }
    }

    /**
     * Plank Make (86): cast on a log, turns it into a plank for 70% of the sawmill price (70, 175,
     * 350, 1,050, 1,750, 3,500 or 5,250 coins), then repeats on the next log of the same kind
     * every 3 ticks.
     */
    private fun ProtectedAccess.plankMake(slot: Int) {
        val log = inv[slot]
        val plank = PLANKS.firstOrNull { log.isType(it.log) }
        if (plank == null) {
            mes("You can only cast Plank Make on logs.")
            return
        }
        if (inv.count(COINS) < plank.cost) {
            mes("You need ${plank.cost} coins to convert that log into a plank.")
            return
        }
        val spell = caster.spell("obj.86_plank_make") ?: return
        if (!caster.cast(player, spell)) {
            return
        }
        anim("seq.dream_player_make_plank_spell")
        spotanim("spotanim.dream_plank_make_spell_spotanim", height = 92)
        invDel(inv, COINS, plank.cost)
        invReplaceSlot(inv, slot, 1, plank.plank.objType())
        val next = inv.indexOfFirst { it.isType(plank.log) }
        if (next != -1) {
            weakQueue(PLANK_QUEUE, PLANK_REPEAT_TICKS, next)
        }
    }

    private data class Pie(val raw: String, val cooked: String, val level: Int, val xp: Double)

    private data class PlankType(val log: String, val plank: String, val cost: Int)

    private companion object {
        const val SUMMON_ANIM = "seq.lunar_human_magic_summon2"
        const val BAKE_PIE_QUEUE = "queue.lunar_bake_pie"
        const val STRING_QUEUE = "queue.lunar_string_jewellery"
        const val PLANK_QUEUE = "queue.lunar_plank_make"
        const val REPEAT_TICKS = 5
        const val PLANK_REPEAT_TICKS = 3
        const val MAX_PER_CAST = 5

        const val HUNTER_KIT = "obj.dream_hunter_box"
        val HUNTER_KIT_TOOLS =
            listOf(
                "obj.noose_wand",
                "obj.hunting_butterfly_net",
                "obj.hunting_ojibway_bird_snare",
                "obj.hunting_snare",
                "obj.hunting_teasing_stick",
                "obj.torch_unlit",
                "obj.hunting_box_trap",
            )
        const val FLAX = "obj.flax"
        const val BOW_STRING = "obj.bow_string"
        const val SPIN_FLAX_LEVEL = 10
        const val SPIN_FLAX_XP = 15.0
        const val SAND = "obj.bucket_sand"
        const val GIANT_SEAWEED = "obj.giant_seaweed"
        const val GIANT_SEAWEED_SAND = 6
        const val MOLTEN_GLASS = "obj.molten_glass"
        const val SUPERGLASS_XP = 10.0
        const val STRING_XP = 4.0
        const val COINS = "obj.coins"

        val SUPERGLASS_SECONDARIES = listOf("obj.soda_ash", "obj.seaweed", "obj.dorgesh_swamp_weed")

        val PIES =
            listOf(
                Pie("obj.uncooked_redberry_pie", "obj.redberry_pie", 10, 78.0),
                Pie("obj.uncooked_meat_pie", "obj.meat_pie", 20, 110.0),
                Pie("obj.uncooked_mud_pie", "obj.mud_pie", 29, 128.0),
                Pie("obj.uncooked_apple_pie", "obj.apple_pie", 30, 130.0),
                Pie("obj.uncooked_garden_pie", "obj.garden_pie", 34, 138.0),
                Pie("obj.uncooked_fish_pie", "obj.fish_pie", 47, 164.0),
                Pie("obj.uncooked_botanical_pie", "obj.botanical_pie", 52, 180.0),
                Pie("obj.uncooked_mushroom_pie", "obj.mushroom_pie", 60, 200.0),
                Pie("obj.uncooked_admiral_pie", "obj.admiral_pie", 70, 210.0),
                Pie("obj.uncooked_dragonfruit_pie", "obj.dragonfruit_pie", 73, 220.0),
                Pie("obj.uncooked_wild_pie", "obj.wild_pie", 85, 240.0),
                Pie("obj.uncooked_summer_pie", "obj.summer_pie", 95, 260.0),
            )

        val HUMIDIFY =
            mapOf(
                "obj.vial_empty" to "obj.vial_water",
                "obj.bowl_empty" to "obj.bowl_water",
                "obj.bucket_empty" to "obj.bucket_water",
                "obj.jug_empty" to "obj.jug_water",
                "obj.cup_empty" to "obj.cup_water",
                "obj.fishbowl_empty" to "obj.fishbowl_water",
                "obj.water_skin0" to "obj.water_skin4",
                "obj.water_skin1" to "obj.water_skin4",
                "obj.water_skin2" to "obj.water_skin4",
                "obj.water_skin3" to "obj.water_skin4",
                "obj.watering_can_0" to "obj.watering_can_8",
                "obj.watering_can_1" to "obj.watering_can_8",
                "obj.watering_can_2" to "obj.watering_can_8",
                "obj.watering_can_3" to "obj.watering_can_8",
                "obj.watering_can_4" to "obj.watering_can_8",
                "obj.watering_can_5" to "obj.watering_can_8",
                "obj.watering_can_6" to "obj.watering_can_8",
                "obj.watering_can_7" to "obj.watering_can_8",
            )

        val HIDES =
            mapOf(
                "obj.cow_hide" to "obj.leather",
                "obj.village_snake_hide" to "obj.village_snake_skin",
                "obj.templetrek_swamp_snake_hide" to "obj.village_snake_skin",
                "obj.dragonhide_green" to "obj.dragon_leather",
                "obj.dragonhide_blue" to "obj.dragon_leather_blue",
                "obj.dragonhide_red" to "obj.dragon_leather_red",
                "obj.dragonhide_black" to "obj.dragon_leather_black",
            )

        val AMULETS =
            mapOf(
                "obj.unstrung_gold_amulet" to "obj.strung_gold_amulet",
                "obj.unstrung_opal_amulet" to "obj.strung_opal_amulet",
                "obj.unstrung_jade_amulet" to "obj.strung_jade_amulet",
                "obj.unstrung_topaz_amulet" to "obj.strung_topaz_amulet",
                "obj.unstrung_sapphire_amulet" to "obj.strung_sapphire_amulet",
                "obj.unstrung_emerald_amulet" to "obj.strung_emerald_amulet",
                "obj.unstrung_ruby_amulet" to "obj.strung_ruby_amulet",
                "obj.unstrung_diamond_amulet" to "obj.strung_diamond_amulet",
                "obj.unstrung_dragonstone_amulet" to "obj.strung_dragonstone_amulet",
                "obj.unstrung_onyx_amulet" to "obj.strung_onyx_amulet",
                "obj.unstrung_zenyte_amulet" to "obj.zenyte_amulet",
            )

        val RECHARGE =
            mapOf(
                "obj.amulet_of_glory" to "obj.amulet_of_glory_4",
                "obj.amulet_of_glory_1" to "obj.amulet_of_glory_4",
                "obj.amulet_of_glory_2" to "obj.amulet_of_glory_4",
                "obj.amulet_of_glory_3" to "obj.amulet_of_glory_4",
                "obj.trail_amulet_of_glory" to "obj.trail_amulet_of_glory_4",
                "obj.trail_amulet_of_glory_1" to "obj.trail_amulet_of_glory_4",
                "obj.trail_amulet_of_glory_2" to "obj.trail_amulet_of_glory_4",
                "obj.trail_amulet_of_glory_3" to "obj.trail_amulet_of_glory_4",
                "obj.jewl_necklace_of_skills" to "obj.jewl_necklace_of_skills_4",
                "obj.jewl_necklace_of_skills_1" to "obj.jewl_necklace_of_skills_4",
                "obj.jewl_necklace_of_skills_2" to "obj.jewl_necklace_of_skills_4",
                "obj.jewl_necklace_of_skills_3" to "obj.jewl_necklace_of_skills_4",
                "obj.jewl_bracelet_of_combat" to "obj.jewl_bracelet_of_combat_4",
                "obj.jewl_bracelet_of_combat_1" to "obj.jewl_bracelet_of_combat_4",
                "obj.jewl_bracelet_of_combat_2" to "obj.jewl_bracelet_of_combat_4",
                "obj.jewl_bracelet_of_combat_3" to "obj.jewl_bracelet_of_combat_4",
            )

        val PLANKS =
            listOf(
                PlankType("obj.logs", "obj.woodplank", 70),
                PlankType("obj.oak_logs", "obj.plank_oak", 175),
                PlankType("obj.teak_logs", "obj.plank_teak", 350),
                PlankType("obj.mahogany_logs", "obj.plank_mahogany", 1050),
                PlankType("obj.camphor_logs", "obj.plank_camphor", 1750),
                PlankType("obj.ironwood_logs", "obj.plank_ironwood", 3500),
                PlankType("obj.rosewood_logs", "obj.plank_rosewood", 5250),
            )

        fun String.asComponent(): Int = asRSCM(RSCMType.COMPONENT)

        fun String.objType(): ItemServerType =
            checkNotNull(ServerCacheManager.getItem(asRSCM(RSCMType.OBJ))) { "Unknown obj: $this" }
    }
}
