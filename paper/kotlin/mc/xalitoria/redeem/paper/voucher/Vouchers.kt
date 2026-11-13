package mc.xalitoria.redeem.paper.voucher

import com.charleskorn.kaml.PolymorphismStyle
import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import com.charleskorn.kaml.YamlException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.nameWithoutExtension

/** The vouchers of `vouchers/`, one file each. A file that cannot be read is reported and left out; the others still load. */
object Vouchers {
    /** [vouchers] by name, in name order, and a line for each file that was left out ([errors]). */
    class Loaded(val vouchers: Map<String, Voucher>, val errors: List<String>)

    // Strict, so that a misspelled key is an error instead of a voucher that quietly does less than it says.
    private val yaml = Yaml(configuration = YamlConfiguration(strictMode = true, polymorphismStyle = PolymorphismStyle.Property, polymorphismPropertyName = "type"))

    /**
     * Reads every `.yml` file of [directory]. [placeholders] is whether PlaceholderAPI is installed, which the `placeholder`
     * condition needs.
     */
    fun load(directory: Path, placeholders: Boolean): Loaded {
        if (!directory.exists()) return Loaded(emptyMap(), emptyList())
        val vouchers = sortedMapOf<String, Voucher>()
        val errors = ArrayList<String>()
        val files = directory.listDirectoryEntries().filter { it.isRegularFile() && it.extension.equals("yml", ignoreCase = true) }.sortedBy { it.fileName.toString() }
        for (file in files) {
            val id = file.nameWithoutExtension
            try {
                val voucher = parse(id, Files.readString(file), placeholders)
                if (vouchers.putIfAbsent(voucher.id, voucher) != null) errors += "${file.fileName}: another file already is the voucher '$id'"
            } catch (e: YamlException) {
                errors += "${file.fileName}: ${e.message}"
            } catch (e: IllegalArgumentException) {
                errors += "${file.fileName}: ${e.message}"
            } catch (e: kotlinx.serialization.SerializationException) {
                errors += "${file.fileName}: ${e.message}"
            }
        }
        return Loaded(vouchers, errors)
    }

    /** The voucher [id] written as [text]. Throws what is wrong with it. */
    fun parse(id: String, text: String, placeholders: Boolean): Voucher {
        val voucher = Voucher(id, yaml.decodeFromString(VoucherConfig.serializer(), text))
        require(placeholders || voucher.config.conditions.none { it is Condition.Placeholder }) {
            "it has a placeholder condition, which needs PlaceholderAPI"
        }
        return voucher
    }

    /** Writes the example voucher into [directory] when there is no such folder yet, so a first start has something to read. */
    fun writeExample(directory: Path) {
        if (directory.exists()) return
        directory.createDirectories()
        val example = Vouchers::class.java.getResourceAsStream("/vouchers/example.yml")?.use { String(it.readAllBytes(), Charsets.UTF_8) } ?: return
        Files.writeString(directory.resolve("example.yml"), example)
    }
}
