package org.rsmod.content.raids.tob.bloat.pack

import dev.openrune.definition.dbtables.DBTable
import dev.openrune.pack.PluginPack

class TobBloatPluginPack : PluginPack() {
    override fun dbTables(): List<DBTable> = listOf(TobXarpusScalesTable.scales())
}
