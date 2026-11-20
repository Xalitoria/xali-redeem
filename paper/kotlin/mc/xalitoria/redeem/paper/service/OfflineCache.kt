package mc.xalitoria.redeem.paper.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * What the database holds of players who are not online here, kept for the placeholders, which are asked for on a region thread
 * and must not wait for it. The first request for a player starts a read in the background and has no answer yet ([get] is null);
 * after that the answer is what was read, and it is read again in the background once it is older than [maxAge] milliseconds
 * while it keeps being asked for. What nobody asks for in [IDLE_MINUTES] minutes is dropped by [sweep].
 *
 * [load] gives null for a player who has no row, which is an answer too: [Read.value] is null then.
 */
class OfflineCache<T : Any>(
    private val scope: CoroutineScope,
    private val maxAge: () -> Long,
    private val failed: (UUID, Exception) -> Unit,
    private val load: suspend (UUID) -> T?,
) {
    class Read<T : Any>(@Volatile var value: T?, @Volatile var at: Long) {
        @Volatile
        var asked = System.currentTimeMillis()
    }

    private val entries = ConcurrentHashMap<UUID, Read<T>>()
    private val loading = ConcurrentHashMap.newKeySet<UUID>()

    /** What was read of [player], or null while the first read is under way. */
    fun get(player: UUID): Read<T>? {
        val entry = entries[player]
        if (entry == null) {
            start(player)
            return null
        }
        val now = System.currentTimeMillis()
        entry.asked = now
        if (now - entry.at > maxAge()) start(player)
        return entry
    }

    /** What is held of [player] is out of date: it is read again on the next request, and answers with what it has until then. */
    fun invalidate(player: UUID) {
        entries[player]?.at = 0
    }

    /** Forgets what is held of [player]. */
    fun forget(player: UUID) {
        entries.remove(player)
    }

    fun clear() = entries.clear()

    /** Drops what has not been asked for lately. Run on a timer. */
    fun sweep() {
        val limit = System.currentTimeMillis() - IDLE_MINUTES * 60_000
        entries.entries.removeIf { it.value.asked < limit }
    }

    private fun start(player: UUID) {
        if (!loading.add(player)) return
        scope.launch {
            try {
                val value = load(player)
                val now = System.currentTimeMillis()
                val entry = entries[player]
                if (entry == null) entries[player] = Read(value, now) else {
                    entry.value = value
                    entry.at = now
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failed(player, e)
            } finally {
                loading.remove(player)
            }
        }
    }

    companion object {
        const val IDLE_MINUTES = 10L
    }
}
