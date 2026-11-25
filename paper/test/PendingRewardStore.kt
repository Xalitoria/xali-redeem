import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import mc.xalitoria.redeem.paper.data.config.MainConfig
import mc.xalitoria.redeem.paper.data.db.DatabaseManager
import mc.xalitoria.redeem.paper.data.db.repository.PendingRecord
import mc.xalitoria.redeem.paper.data.db.repository.PendingRewardRepository
import java.nio.file.Files
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Tests storing, listing, expiring, claiming, unclaiming, and racing dynamic pending rewards in SQLite. */
class PendingRewardStore {
    private val alice = UUID.randomUUID()
    private val bob = UUID.randomUUID()

    @BeforeTest
    fun database() {
        val file = Files.createTempFile("xaliredeem-pending-test", ".db").also { it.toFile().deleteOnExit() }
        val config = HikariConfig().apply {
            jdbcUrl = "jdbc:sqlite:$file"
            driverClassName = "org.sqlite.JDBC"
            maximumPoolSize = 1
        }
        DatabaseManager.attach(HikariDataSource(config), MainConfig().database)
    }

    private fun run(block: suspend () -> Unit) = runBlocking<Unit> { block() }

    private fun sample(player: UUID, title: String = "&aCrate Overflow", expiresAt: Long = 0L) = PendingRecord(
        id = 0L,
        player = player,
        source = "xalicrates:master",
        title = title,
        description = ["&7Won from Master Crate"],
        iconBytes = byteArrayOf(1, 2, 3),
        itemBytes = listOf(byteArrayOf(10, 20), byteArrayOf(30, 40, 50)),
        commands = ["say hi {player}"],
        playerCommands = [],
        messages = ["&aEnjoy!"],
        broadcasts = [],
        requiredSlots = -1,
        createdAt = 1_000L,
        expiresAt = expiresAt,
    )

    @Test
    fun `item byte framing round-trips cleanly`() {
        val items = listOf(byteArrayOf(1, 2, 3), ByteArray(0), byteArrayOf(9, 8, 7, 6))
        val encoded = PendingRewardRepository.encodeItems(items)
        val decoded = PendingRewardRepository.decodeItems(encoded)
        assertEquals(3, decoded.size)
        for (i in items.indices) assertContentEquals(items[i], decoded[i])
        assertEquals(emptyList(), PendingRewardRepository.decodeItems(ByteArray(0)))
    }

    @Test
    fun `pending rewards are stored, listed and filtered by player and expiry`() = run {
        val id1 = PendingRewardRepository.insert(sample(alice, "&aSword"))
        val id2 = PendingRewardRepository.insert(sample(alice, "&eExpired", expiresAt = 5_000L))
        val id3 = PendingRewardRepository.insert(sample(bob, "&bBob's Loot"))

        val aliceAt2k = PendingRewardRepository.listUnclaimed(alice, now = 2_000L)
        assertEquals(listOf(id1, id2), aliceAt2k.map { it.id })
        assertEquals(2, aliceAt2k[0].effectiveRequiredSlots())
        assertContentEquals(byteArrayOf(1, 2, 3), aliceAt2k[0].iconBytes)

        val aliceAt6k = PendingRewardRepository.listUnclaimed(alice, now = 6_000L)
        assertEquals(listOf(id1), aliceAt6k.map { it.id })

        val bobList = PendingRewardRepository.listUnclaimed(bob, now = 2_000L)
        assertEquals(listOf(id3), bobList.map { it.id })
    }

    @Test
    fun `claiming is atomic and can be rolled back`() = run {
        val id = PendingRewardRepository.insert(sample(alice))
        // Bob cannot claim Alice's reward
        assertNull(PendingRewardRepository.claim(bob, id, now = 2_000L))

        val claimed = PendingRewardRepository.claim(alice, id, now = 2_000L)
        assertNotNull(claimed)
        assertEquals(id, claimed.id)
        // Second claim fails
        assertNull(PendingRewardRepository.claim(alice, id, now = 2_001L))
        assertEquals(emptyList(), PendingRewardRepository.listUnclaimed(alice, now = 2_001L))

        // Rollback puts it back
        assertTrue(PendingRewardRepository.unclaim(alice, id))
        assertEquals(1, PendingRewardRepository.listUnclaimed(alice, now = 2_002L).size)
        assertNotNull(PendingRewardRepository.claim(alice, id, now = 2_003L))
    }

    @Test
    fun `concurrent claims only succeed once`() = run {
        val id = PendingRewardRepository.insert(sample(alice))
        val results = coroutineScope {
            (1..8).map { async(Dispatchers.Default) { PendingRewardRepository.claim(alice, id, now = 2_000L) } }.awaitAll()
        }
        assertEquals(1, results.count { it != null })
    }

    @Test
    fun `removing and purging work as expected`() = run {
        val id1 = PendingRewardRepository.insert(sample(alice))
        val id2 = PendingRewardRepository.insert(sample(alice, expiresAt = 3_000L))
        assertTrue(PendingRewardRepository.remove(alice, id1))
        assertFalse(PendingRewardRepository.remove(alice, id1))
        assertEquals(1, PendingRewardRepository.purgeExpired(now = 5_000L))
        assertNull(PendingRewardRepository.get(alice, id2))
    }
}
