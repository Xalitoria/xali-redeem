package mc.xalitoria.redeem.paper.data.db.repository

import kotlinx.serialization.json.Json
import mc.xalitoria.redeem.paper.data.db.DatabaseManager.dbTransaction
import mc.xalitoria.redeem.paper.data.db.tables.PendingRewardTable
import net.xalitoria.redeem.PendingReward
import net.xalitoria.redeem.StoredReward
import org.bukkit.inventory.ItemStack
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.UUID
import kotlin.uuid.toJavaUuid
import kotlin.uuid.toKotlinUuid

/**
 * Raw persisted representation of a pending reward, kept platform-free so repository tests run without a Paper server.
 */
data class PendingRecord(
    val id: Long,
    val player: UUID,
    val source: String?,
    val title: String,
    val description: List<String>,
    val iconBytes: ByteArray?,
    val itemBytes: List<ByteArray>,
    val commands: List<String>,
    val playerCommands: List<String>,
    val messages: List<String>,
    val broadcasts: List<String>,
    val requiredSlots: Int,
    val createdAt: Long,
    val expiresAt: Long,
) {
    fun effectiveRequiredSlots(): Int = if (requiredSlots >= 0) requiredSlots else itemBytes.size

    fun toStoredReward(): StoredReward {
        val builder = PendingReward.builder(title)
            .description(description)
            .commands(commands)
            .playerCommands(playerCommands)
            .messages(messages)
            .broadcasts(broadcasts)
            .requiredSlots(requiredSlots)
            .source(source)
            .expiresAt(expiresAt)
        iconBytes?.let { bytes ->
            runCatching { ItemStack.deserializeBytes(bytes) }.getOrNull()?.let { builder.icon(it) }
        }
        val items = itemBytes.mapNotNull { bytes -> runCatching { ItemStack.deserializeBytes(bytes) }.getOrNull() }
        builder.items(items)
        return StoredReward(id, player, builder.build(), createdAt)
    }

    companion object {
        fun fromReward(player: UUID, reward: PendingReward, createdAt: Long): PendingRecord = PendingRecord(
            id = 0L,
            player = player,
            source = reward.source?.take(128),
            title = reward.title.take(256),
            description = reward.description,
            iconBytes = reward.icon?.serializeAsBytes(),
            itemBytes = reward.items.map { it.serializeAsBytes() },
            commands = reward.commands,
            playerCommands = reward.playerCommands,
            messages = reward.messages,
            broadcasts = reward.broadcasts,
            requiredSlots = reward.requiredSlots,
            createdAt = createdAt,
            expiresAt = reward.expiresAt,
        )
    }
}

/**
 * Persistence for ad-hoc dynamic rewards stored in [PendingRewardTable]. Claiming uses a single guarded `UPDATE` so two
 * servers or a double click can never grant the same reward twice.
 */
object PendingRewardRepository {
    private val json = Json { ignoreUnknownKeys = true }

    internal fun encodeItems(items: List<ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { data ->
            data.writeInt(items.size)
            for (item in items) {
                data.writeInt(item.size)
                data.write(item)
            }
        }
        return out.toByteArray()
    }

    internal fun decodeItems(bytes: ByteArray): List<ByteArray> {
        if (bytes.isEmpty()) return []
        DataInputStream(ByteArrayInputStream(bytes)).use { data ->
            val count = data.readInt().coerceAtLeast(0)
            val result = ArrayList<ByteArray>(count)
            repeat(count) {
                val len = data.readInt()
                require(len >= 0) { "Negative item length in pending reward payload" }
                val buf = ByteArray(len)
                data.readFully(buf)
                result.add(buf)
            }
            return result
        }
    }

    private fun decodeStrings(text: String): List<String> =
        if (text.isBlank()) [] else runCatching { json.decodeFromString<List<String>>(text) }.getOrElse { [] }

    private fun encodeStrings(list: List<String>): String = json.encodeToString(list)

    private fun ResultRow.toRecord(): PendingRecord = PendingRecord(
        id = this[PendingRewardTable.id].value,
        player = this[PendingRewardTable.player].toJavaUuid(),
        source = this[PendingRewardTable.origin],
        title = this[PendingRewardTable.title],
        description = decodeStrings(this[PendingRewardTable.description]),
        iconBytes = this[PendingRewardTable.iconBytes],
        itemBytes = decodeItems(this[PendingRewardTable.itemsBytes]),
        commands = decodeStrings(this[PendingRewardTable.commands]),
        playerCommands = decodeStrings(this[PendingRewardTable.playerCommands]),
        messages = decodeStrings(this[PendingRewardTable.messages]),
        broadcasts = decodeStrings(this[PendingRewardTable.broadcasts]),
        requiredSlots = this[PendingRewardTable.requiredSlots],
        createdAt = this[PendingRewardTable.createdAt],
        expiresAt = this[PendingRewardTable.expiresAt],
    )

    suspend fun insert(record: PendingRecord): Long = dbTransaction {
        PendingRewardTable.insertAndGetId {
            it[player] = record.player.toKotlinUuid()
            it[origin] = record.source?.take(128)
            it[title] = record.title.take(256)
            it[description] = encodeStrings(record.description)
            it[iconBytes] = record.iconBytes
            it[itemsBytes] = encodeItems(record.itemBytes)
            it[commands] = encodeStrings(record.commands)
            it[playerCommands] = encodeStrings(record.playerCommands)
            it[messages] = encodeStrings(record.messages)
            it[broadcasts] = encodeStrings(record.broadcasts)
            it[requiredSlots] = record.requiredSlots
            it[createdAt] = record.createdAt
            it[expiresAt] = record.expiresAt
            it[claimed] = false
        }.value
    }

    suspend fun listUnclaimed(player: UUID, now: Long = System.currentTimeMillis()): List<PendingRecord> = dbTransaction {
        PendingRewardTable.selectAll()
            .where {
                (PendingRewardTable.player eq player.toKotlinUuid()) and
                    (PendingRewardTable.claimed eq false) and
                    ((PendingRewardTable.expiresAt eq 0L) or (PendingRewardTable.expiresAt greater now))
            }
            .orderBy(PendingRewardTable.id, SortOrder.ASC)
            .map { it.toRecord() }
    }

    suspend fun get(player: UUID, id: Long): PendingRecord? = dbTransaction {
        PendingRewardTable.selectAll()
            .where { (PendingRewardTable.id eq id) and (PendingRewardTable.player eq player.toKotlinUuid()) }
            .singleOrNull()?.toRecord()
    }

    /** Atomically marks [id] as claimed if it belongs to [player], is unclaimed, and is not expired. */
    suspend fun claim(player: UUID, id: Long, now: Long = System.currentTimeMillis()): PendingRecord? = dbTransaction {
        val updated = PendingRewardTable.update({
            (PendingRewardTable.id eq id) and
                (PendingRewardTable.player eq player.toKotlinUuid()) and
                (PendingRewardTable.claimed eq false) and
                ((PendingRewardTable.expiresAt eq 0L) or (PendingRewardTable.expiresAt greater now))
        }) {
            it[claimed] = true
        }
        if (updated == 0) return@dbTransaction null
        PendingRewardTable.selectAll()
            .where { PendingRewardTable.id eq id }
            .singleOrNull()?.toRecord()
    }

    /** Reverses [claim] when delivery could not complete (e.g. inventory filled or player disconnected). */
    suspend fun unclaim(player: UUID, id: Long): Boolean = dbTransaction {
        PendingRewardTable.update({
            (PendingRewardTable.id eq id) and (PendingRewardTable.player eq player.toKotlinUuid()) and (PendingRewardTable.claimed eq true)
        }) {
            it[claimed] = false
        } > 0
    }

    /** Deletes an unclaimed reward without granting it. */
    suspend fun remove(player: UUID, id: Long): Boolean = dbTransaction {
        PendingRewardTable.deleteWhere {
            (PendingRewardTable.id eq id) and (PendingRewardTable.player eq player.toKotlinUuid()) and (PendingRewardTable.claimed eq false)
        } > 0
    }

    /** Deletes claimed rows and expired unclaimed rows older than [now]. */
    suspend fun purgeExpired(now: Long = System.currentTimeMillis()): Int = dbTransaction {
        PendingRewardTable.deleteWhere {
            (PendingRewardTable.claimed eq true) or
                ((PendingRewardTable.expiresAt greater 0L) and (PendingRewardTable.expiresAt lessEq now))
        }
    }
}
