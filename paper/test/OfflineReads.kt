import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import mc.xalitoria.redeem.paper.service.OfflineCache
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What the placeholders keep of a player who is not online: read in the background, never waited for. */
class OfflineReads {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val reads = AtomicInteger()
    private val rows = HashMap<UUID, Long>()
    private val failures = ArrayList<UUID>()
    private var maxAge = 60_000L
    private val cache = OfflineCache<Long>(scope, { maxAge }, { player, _ -> failures.add(player) }) {
        reads.incrementAndGet()
        rows[it]
    }
    private val alice = UUID.randomUUID()

    @AfterTest
    fun stop() = scope.cancel()

    private fun <T : Any> waitFor(read: () -> T?): T? {
        repeat(200) {
            read()?.let { return it }
            Thread.sleep(10)
        }
        return null
    }

    @Test
    fun `the first request has no answer and starts one read`() {
        rows[alice] = 7
        assertNull(cache.get(alice))
        cache.get(alice)
        assertEquals(7, waitFor { cache.get(alice) }?.value)
        assertEquals(1, reads.get())
    }

    @Test
    fun `a player with no row is an answer, not a missing one`() {
        val read = waitFor { cache.get(alice) }
        assertNotNull(read)
        assertNull(read.value)
    }

    @Test
    fun `an old answer is given again while it is read anew`() {
        rows[alice] = 1
        waitFor { cache.get(alice) }
        rows[alice] = 2
        cache.invalidate(alice)
        assertEquals(1, cache.get(alice)?.value)
        assertEquals(2, waitFor { cache.get(alice)?.takeIf { it.value == 2L } }?.value)
        maxAge = 0
        rows[alice] = 3
        Thread.sleep(5)
        cache.get(alice)
        assertEquals(3, waitFor { cache.get(alice)?.takeIf { it.value == 3L } }?.value)
    }

    @Test
    fun `a read that fails is told and tried again on the next request`() {
        val broken = OfflineCache<Long>(scope, { 60_000 }, { player, _ -> failures.add(player) }) { error("down") }
        assertNull(broken.get(alice))
        assertTrue(waitFor { failures.firstOrNull() } == alice)
        assertNull(broken.get(alice))
    }

    @Test
    fun `forgotten players are read again`() {
        rows[alice] = 1
        waitFor { cache.get(alice) }
        cache.forget(alice)
        assertNull(cache.get(alice))
        assertEquals(1, waitFor { cache.get(alice) }?.value)
        assertEquals(2, reads.get())
    }
}
