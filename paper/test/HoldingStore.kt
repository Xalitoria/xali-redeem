import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import mc.xalitoria.redeem.paper.data.config.MainConfig
import mc.xalitoria.redeem.paper.data.db.DatabaseManager
import mc.xalitoria.redeem.paper.data.db.repository.HoldingRepository
import mc.xalitoria.redeem.paper.data.model.Consume
import mc.xalitoria.redeem.paper.data.model.Holding
import java.nio.file.Files
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** The rules of holding and using vouchers against a real SQLite file. The tables read the prefix once, so one database serves a class. */
class HoldingStore {
    private val alice = UUID.randomUUID()
    private val bob = UUID.randomUUID()

    @BeforeTest
    fun database() {
        val file = Files.createTempFile("xaliredeem-test", ".db").also { it.toFile().deleteOnExit() }
        val config = HikariConfig().apply {
            jdbcUrl = "jdbc:sqlite:$file"
            driverClassName = "org.sqlite.JDBC"
            maximumPoolSize = 1
        }
        DatabaseManager.attach(HikariDataSource(config), MainConfig().database)
    }

    private fun run(block: suspend () -> Unit) = runBlocking { block() }

    @Test
    fun `vouchers are given and taken`() = run {
        assertEquals(Holding.NONE, HoldingRepository.get(alice, "crate"))
        assertEquals(2, HoldingRepository.give(alice, "crate", 2))
        assertEquals(5, HoldingRepository.give(alice, "crate", 3))
        assertEquals(2, HoldingRepository.take(alice, "crate", 2))
        assertEquals(3, HoldingRepository.get(alice, "crate").amount)
        assertEquals(mapOf("crate" to 3), HoldingRepository.of(alice).mapValues { it.value.amount })
        assertEquals(emptyMap(), HoldingRepository.of(bob))
    }

    @Test
    fun `taking more than there is takes what there is`() = run {
        HoldingRepository.give(alice, "crate", 2)
        assertEquals(2, HoldingRepository.take(alice, "crate", 10))
        assertEquals(0, HoldingRepository.get(alice, "crate").amount)
        assertEquals(0, HoldingRepository.take(alice, "crate", 1))
        assertEquals(0, HoldingRepository.take(bob, "crate", 1))
    }

    @Test
    fun `a held voucher is used up once each`() = run {
        HoldingRepository.give(alice, "crate", 2)
        assertIs<Consume.Done>(HoldingRepository.consume(alice, "crate", needsHeld = true, cooldown = 0, max = 0, now = 1_000))
        assertIs<Consume.Done>(HoldingRepository.consume(alice, "crate", needsHeld = true, cooldown = 0, max = 0, now = 1_001))
        assertEquals(Consume.NoneHeld, HoldingRepository.consume(alice, "crate", needsHeld = true, cooldown = 0, max = 0, now = 1_002))
        val held = HoldingRepository.get(alice, "crate")
        assertEquals(0, held.amount)
        assertEquals(2, held.redeemed)
        assertEquals(1_001, held.last)
        assertEquals(Consume.NoneHeld, HoldingRepository.consume(bob, "crate", needsHeld = true, cooldown = 0, max = 0, now = 1_000))
    }

    @Test
    fun `a free voucher holds nothing and has a cooldown`() = run {
        val day = 86_400_000L
        assertEquals(Consume.Done(0), HoldingRepository.consume(alice, "daily", needsHeld = false, cooldown = day, max = 0, now = day))
        val again = HoldingRepository.consume(alice, "daily", needsHeld = false, cooldown = day, max = 0, now = day + 1_000)
        assertEquals(Consume.Cooling(day - 1_000), again)
        assertIs<Consume.Done>(HoldingRepository.consume(alice, "daily", needsHeld = false, cooldown = day, max = 0, now = 2 * day))
        // Someone else's cooldown is their own.
        assertIs<Consume.Done>(HoldingRepository.consume(bob, "daily", needsHeld = false, cooldown = day, max = 0, now = day + 1_000))
        assertEquals(0, HoldingRepository.get(alice, "daily").amount)
    }

    @Test
    fun `a voucher can be redeemed only as often as it allows`() = run {
        HoldingRepository.give(alice, "gift", 5)
        assertIs<Consume.Done>(HoldingRepository.consume(alice, "gift", needsHeld = true, cooldown = 0, max = 2, now = 10))
        assertIs<Consume.Done>(HoldingRepository.consume(alice, "gift", needsHeld = true, cooldown = 0, max = 2, now = 11))
        assertEquals(Consume.LimitReached, HoldingRepository.consume(alice, "gift", needsHeld = true, cooldown = 0, max = 2, now = 12))
        assertEquals(3, HoldingRepository.get(alice, "gift").amount)
    }

    @Test
    fun `a redeem that could not be finished is given back`() = run {
        HoldingRepository.give(alice, "crate", 1)
        val first = HoldingRepository.consume(alice, "crate", needsHeld = true, cooldown = 1_000, max = 1, now = 5_000)
        assertEquals(Consume.Done(0), first)
        HoldingRepository.refund(alice, "crate", needsHeld = true, previousLast = 0)
        assertEquals(Holding(amount = 1, redeemed = 0, last = 0), HoldingRepository.get(alice, "crate"))
        assertIs<Consume.Done>(HoldingRepository.consume(alice, "crate", needsHeld = true, cooldown = 1_000, max = 1, now = 5_001))
    }

    @Test
    fun `a player's usage can be started again`() = run {
        HoldingRepository.give(alice, "crate", 3)
        HoldingRepository.consume(alice, "crate", needsHeld = true, cooldown = 0, max = 1, now = 7)
        assertEquals(Consume.LimitReached, HoldingRepository.consume(alice, "crate", needsHeld = true, cooldown = 0, max = 1, now = 8))
        assertEquals(true, HoldingRepository.resetUsage(alice, "crate"))
        assertIs<Consume.Done>(HoldingRepository.consume(alice, "crate", needsHeld = true, cooldown = 0, max = 1, now = 9))
        assertEquals(false, HoldingRepository.resetUsage(bob, "crate"))
    }

    @Test
    fun `the last voucher goes to one of the redeems that race`() = run {
        HoldingRepository.give(alice, "crate", 1)
        val results = coroutineScope {
            (1..8).map { async(Dispatchers.Default) { HoldingRepository.consume(alice, "crate", needsHeld = true, cooldown = 0, max = 0, now = 1) } }.awaitAll()
        }
        assertEquals(1, results.count { it is Consume.Done })
        assertEquals(0, HoldingRepository.get(alice, "crate").amount)
    }
}
