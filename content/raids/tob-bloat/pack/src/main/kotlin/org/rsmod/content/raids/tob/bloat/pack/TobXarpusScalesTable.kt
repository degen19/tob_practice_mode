package org.rsmod.content.raids.tob.bloat.pack

import dev.openrune.definition.dbtables.DBTable
import dev.openrune.definition.dbtables.dbTable
import dev.openrune.definition.util.VarType

/**
 * Xarpus by party size (Blert, the 50 fastest Normal rooms of each scale): his hitpoints, what each exhumed heal adds,
 * how many exhumeds come up and how many ticks apart.
 */
object TobXarpusScalesTable {
    const val PARTY = 0
    const val HITPOINTS = 1
    const val HEAL = 2
    const val EXHUMEDS = 3
    const val EXHUMED_GAP = 4

    fun scales(): DBTable =
        dbTable("dbtable.tob_xarpus_scales", serverOnly = true) {
            column("party", PARTY, VarType.INT)
            column("hitpoints", HITPOINTS, VarType.INT)
            column("heal", HEAL, VarType.INT)
            column("exhumeds", EXHUMEDS, VarType.INT)
            column("exhumed_gap", EXHUMED_GAP, VarType.INT)

            fun scale(row: String, party: Int, hitpoints: Int, heal: Int, exhumeds: Int, gap: Int) {
                row(row) {
                    column(PARTY, party)
                    column(HITPOINTS, hitpoints)
                    column(HEAL, heal)
                    column(EXHUMEDS, exhumeds)
                    column(EXHUMED_GAP, gap)
                }
            }

            scale("dbrow.tob_xarpus_scale_solo", 1, 3750, 20, 7, 12)
            scale("dbrow.tob_xarpus_scale_duo", 2, 3750, 16, 9, 8)
            scale("dbrow.tob_xarpus_scale_trio", 3, 3750, 12, 12, 8)
            scale("dbrow.tob_xarpus_scale_four", 4, 4375, 9, 15, 4)
            scale("dbrow.tob_xarpus_scale_five", 5, 5000, 8, 18, 4)
        }
}
