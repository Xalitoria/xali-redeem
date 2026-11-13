import com.xalitoria.util.message.TimeWords
import com.xalitoria.util.config.DatabaseType
import com.xalitoria.util.config.YamlFile
import com.xalitoria.util.config.databaseType
import com.xalitoria.util.kotlin.parseDuration
import mc.xalitoria.redeem.paper.data.config.MainConfig
import mc.xalitoria.redeem.paper.data.config.MessageConfig
import mc.xalitoria.redeem.paper.voucher.Condition
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ConfigFile {
    @Test
    fun `the defaults are valid`() {
        val config = MainConfig()
        config.validate()
        assertEquals(DatabaseType.SQLITE, config.database.databaseType)
        assertEquals("xaliredeem_", config.database.tablePrefix)
        assertTrue(config.menu.enabled)
        assertEquals("redeem", config.menu.name)
    }

    @Test
    fun `a menu name that is not a file name is refused`() {
        assertFailsWith<IllegalArgumentException> { MainConfig(menu = MainConfig.Menu(name = "a b")).validate() }
        assertFailsWith<IllegalArgumentException> { MainConfig(menu = MainConfig.Menu(name = "../x")).validate() }
        assertFailsWith<IllegalArgumentException> { MainConfig(perPage = 0).validate() }
    }

    @Test
    fun `a file is written with the defaults and read back`() {
        val dir = Files.createTempDirectory("xaliredeem-config")
        val file = YamlFile(dir.resolve("config.yml"), MainConfig.serializer(), ::MainConfig)
        file.load()
        val text = Files.readString(dir.resolve("config.yml"))
        assertTrue("write-menu" in text, text)
        assertTrue("per-page" in text, text)

        Files.writeString(dir.resolve("config.yml"), text.replace("enabled: true", "enabled: false"))
        val again = YamlFile(dir.resolve("config.yml"), MainConfig.serializer(), ::MainConfig)
        again.load()
        assertEquals(false, again.data.menu.enabled)

        val messages = YamlFile(dir.resolve("en-us.yml"), MessageConfig.serializer(), ::MessageConfig)
        messages.load()
        messages.save()
        assertEquals("1 hour 5 minutes", messages.data.words.duration(3900))
    }

    @Test
    fun `times are written in words`() {
        val words = TimeWords()
        assertEquals("0 seconds", words.duration(0))
        assertEquals("1 second", words.duration(1))
        assertEquals("2 hours", words.duration(7200))
        assertEquals("1 day 1 hour 1 minute 1 second", words.duration(90_061))
    }

    @Test
    fun `times may be mixed units and a wrong one names the setting`() {
        assertEquals(5400, parseDuration("cooldown", "1h 30m").inWholeSeconds)
        assertEquals(0, parseDuration("cooldown", "0s").inWholeSeconds)
        val error = assertFailsWith<IllegalArgumentException> { parseDuration("cooldown", "tomorrow") }
        assertTrue("cooldown" in error.message!!, error.message)
        assertFailsWith<IllegalArgumentException> { parseDuration("cooldown", "-5m") }
    }

    @Test
    fun `a condition tells the player its own message first`() {
        val words = TimeWords()
        val messages = MessageConfig.Conditions()
        assertEquals("You need 3 free inventory slots.", messages.failure(Condition.InventorySpace(3), words))
        assertEquals("Only for VIPs", messages.failure(Condition.Permission("vip", "Only for VIPs"), words))
        assertEquals("You have to have played for 2 hours first.", messages.failure(Condition.Playtime("2h"), words))
    }
}
