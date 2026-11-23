package org.rsmod.content.other.consumables.potion

import dev.openrune.ServerCacheManager
import dev.openrune.rscm.RSCM.asRSCM
import dev.openrune.types.ItemServerType
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.api.parallel.ResourceLock
import org.rsmod.annotations.InternalApi
import org.rsmod.api.inv.storage.PlayerItemStorage
import org.rsmod.api.invtx.InvTransactionsScript
import org.rsmod.api.player.protect.ProtectedAccess
import org.rsmod.api.player.protect.ProtectedAccessContextFactory
import org.rsmod.api.player.protect.ProtectedAccessLauncher
import org.rsmod.events.EventBus
import org.rsmod.game.cheat.CheatCommandMap
import org.rsmod.game.client.Client
import org.rsmod.game.entity.Player
import org.rsmod.game.inv.InvObj
import org.rsmod.game.inv.InvVirtualStorageHolder
import org.rsmod.game.inv.Inventory
import org.rsmod.game.queue.EngineQueueCache
import org.rsmod.plugin.scripts.ScriptContext

@Execution(ExecutionMode.SAME_THREAD)
@ResourceLock("ServerCacheManager")
class PotionEmptyTest {
    @Test
    fun `empty swaps the potion for its vial in the same slot`() {
        val p = player()
        p.inv[0] = InvObj(SHARK)
        p.inv[3] = InvObj(RESTORE)
        assertTrue(run(p) { emptyPotion(p.inv, 3, item(RESTORE), item(VIAL)) })
        assertEquals(VIAL.asRSCM(), p.inv[3]?.id)
        assertEquals(1, p.inv.count(VIAL))
        assertEquals(0, p.inv.count(RESTORE))
        assertEquals(1, p.inv.count(SHARK))
    }

    @Test
    fun `empty works with a full inventory`() {
        val p = player()
        for (slot in p.inv.indices) p.inv[slot] = InvObj(SHARK)
        p.inv[27] = InvObj(RESTORE)
        assertTrue(run(p) { emptyPotion(p.inv, 27, item(RESTORE), item(VIAL)) })
        assertEquals(27, p.inv.count(SHARK))
        assertEquals(1, p.inv.count(VIAL))
    }

    @Test
    fun `a repeated or stale click changes nothing`() {
        val p = player()
        p.inv[5] = InvObj(RESTORE)
        assertTrue(run(p) { emptyPotion(p.inv, 5, item(RESTORE), item(VIAL)) })
        val after = snapshot(p)
        assertFalse(run(p) { emptyPotion(p.inv, 5, item(RESTORE), item(VIAL)) })
        assertFalse(run(p) { emptyPotion(p.inv, 6, item(RESTORE), item(VIAL)) })
        assertEquals(after, snapshot(p))
    }

    @Test
    fun `a potion that moved before the click lands is left alone`() {
        val p = player()
        p.inv[2] = InvObj(RESTORE)
        p.inv[9] = InvObj(SHARK)
        val before = snapshot(p)
        assertFalse(run(p) { emptyPotion(p.inv, 9, item(RESTORE), item(VIAL)) })
        assertEquals(before, snapshot(p))
    }

    private fun run(p: Player, block: ProtectedAccess.() -> Boolean): Boolean {
        var result = false
        check(ProtectedAccessLauncher.withProtectedAccess(p, ProtectedAccessContextFactory.empty()) { result = block() }) { "player busy" }
        return result
    }

    private fun snapshot(p: Player) = p.inv.indices.map { p.inv[it]?.let { o -> o.id to o.count } }

    @OptIn(InternalApi::class)
    private fun player(): Player =
        Player().apply {
            client = SilentClient()
            uuid = 803L
            slotId = 1
            assignUid()
            currentMapClock = 100
            processedMapClock = 100
            inv = Inventory(checkNotNull(ServerCacheManager.getInventory("inv.inv".asRSCM())), arrayOfNulls(28))
        }

    private class SilentClient : Client<Any, Any> {
        override fun write(message: Any) {}

        override fun close() {}

        override fun read(player: Player) {}

        override fun flush() {}

        override fun flushHighPriority() {}

        override fun unregister(service: Any, player: Player) {}
    }

    companion object {
        private const val RESTORE = "obj.4dose2restore"
        private const val VIAL = "obj.vial_empty"
        private const val SHARK = "obj.shark"
        private val restored = mutableListOf<() -> Unit>()

        private fun item(obj: String): ItemServerType = checkNotNull(ServerCacheManager.getItem(obj.asRSCM())) { obj }

        @OptIn(InternalApi::class)
        @JvmStatic
        @BeforeAll
        fun cache() {
            val root = generateSequence(Path.of("").toAbsolutePath()) { it.parent }.first { Files.isRegularFile(it.resolve("game.yml")) }
            val revision = Files.readAllLines(root.resolve("game.yml")).first { it.startsWith("revision:") }.substringAfter(':').trim().substringBefore('.').toInt()
            ServerCacheManager.init(revision).close()
            for ((owner, name) in
                listOf(
                    "org.rsmod.api.invtx.InvTransactionsScriptKt" to "cachedInventoryTransactions",
                    "org.rsmod.api.invtx.VirtualInvTransactionsKt" to "cachedPlayerItemStorage",
                )) {
                val field = Class.forName(owner).getDeclaredField(name).apply { isAccessible = true }
                val old = field.get(null)
                restored += { field.set(null, old) }
            }
            val oldStorage = InvVirtualStorageHolder.instance
            restored += { InvVirtualStorageHolder.instance = oldStorage }
            with(InvTransactionsScript(PlayerItemStorage(emptySet()))) {
                ScriptContext(EventBus(), CheatCommandMap(), EngineQueueCache()).startup()
            }
        }

        @JvmStatic
        @AfterAll
        fun restore() {
            restored.asReversed().forEach { it() }
            restored.clear()
        }
    }
}
