import mc.xalitoria.redeem.paper.voucher.Condition
import mc.xalitoria.redeem.paper.voucher.Show
import mc.xalitoria.redeem.paper.voucher.Vouchers
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class VoucherFiles {
    private fun parse(id: String, text: String, placeholders: Boolean = false) = Vouchers.parse(id, text.trimIndent(), placeholders)

    @Test
    fun `the example that is written on a first start is a voucher`() {
        val dir = Files.createTempDirectory("xaliredeem-vouchers").resolve("vouchers")
        Vouchers.writeExample(dir)
        val loaded = Vouchers.load(dir, placeholders = false)
        assertEquals([], loaded.errors)
        val example = loaded.vouchers.getValue("example")
        assertEquals("&6Starter Crate", example.name)
        assertFalse(example.free)
        assertEquals(Show.HELD, example.config.show)
        assertEquals(Condition.InventorySpace(2), example.config.conditions.single())
        assertEquals(["give {player} diamond 3"], example.config.rewards.commands)
        assertEquals(16, example.config.rewards.items.single().amount)
    }

    @Test
    fun `the example is not written again once the folder exists`() {
        val dir = Files.createTempDirectory("xaliredeem-vouchers").resolve("vouchers")
        Vouchers.writeExample(dir)
        Files.delete(dir.resolve("example.yml"))
        Vouchers.writeExample(dir)
        assertEquals(0, Vouchers.load(dir, placeholders = false).vouchers.size)
    }

    @Test
    fun `every kind of condition is read`() {
        val voucher = parse("daily", """
            name: Daily
            free: true
            show: READY
            cooldown: 1d
            max-redemptions: 5
            conditions:
              - type: inventory-space
                slots: 3
              - type: permission
                node: a.b
                message: "&cNo"
              - type: permission-any
                nodes: [x, y]
              - type: no-permission
                node: banned
              - type: world
                worlds: [world]
                invert: true
              - type: playtime
                min: 2h
              - type: placeholder
                value: "%vault_eco_balance%"
                operator: ">="
                expected: "100"
        """, placeholders = true)
        assertTrue(voucher.free)
        assertFalse(voucher.needsHeld)
        assertEquals(86_400, voucher.cooldown.inWholeSeconds)
        assertEquals(5, voucher.config.maxRedemptions)
        val conditions = voucher.config.conditions
        assertEquals(7, conditions.size)
        assertEquals("&cNo", assertIs<Condition.Permission>(conditions[1]).message)
        assertEquals(["x", "y"], assertIs<Condition.PermissionAny>(conditions[2]).nodes)
        assertTrue(assertIs<Condition.World>(conditions[4]).invert)
        assertEquals(7200, assertIs<Condition.Playtime>(conditions[5]).seconds)
        assertEquals(">=", assertIs<Condition.Placeholder>(conditions[6]).operator)
    }

    @Test
    fun `a wrong voucher says what is wrong`() {
        assertFailsWith<Exception> { parse("a", "description: [x]") } // no name
        assertFailsWith<Exception> { parse("a", "name: A\nnmae: B") } // a misspelled key
        assertFailsWith<Exception> { parse("a", "name: A\nconditions:\n  - type: nothing") } // an unknown condition
        assertFailsWith<IllegalArgumentException> { parse("a", "name: A\ncooldown: soon") }
        assertFailsWith<IllegalArgumentException> { parse("a", "name: A\nmax-redemptions: -1") }
        assertFailsWith<IllegalArgumentException> { parse("a", "name: A\nconditions:\n  - type: inventory-space\n    slots: 0") }
        assertFailsWith<IllegalArgumentException> { parse("a", "name: A\nconditions:\n  - type: permission-any\n    nodes: []") }
        assertFailsWith<IllegalArgumentException> { parse("a", "name: A\nconditions:\n  - type: playtime\n    min: long") }
        assertFailsWith<IllegalArgumentException> { parse("a", "name: A\nconditions:\n  - type: placeholder\n    value: x\n    operator: \"~\"\n    expected: y", placeholders = true) }
        assertFailsWith<IllegalArgumentException> { parse("a", "name: A\nrewards:\n  items:\n    - material: not_an_item") }
        assertFailsWith<IllegalArgumentException> { parse("a", "name: A\nrewards:\n  items:\n    - material: bread\n      amount: 0") }
    }

    @Test
    fun `a placeholder condition needs PlaceholderAPI`() {
        val text = "name: A\nconditions:\n  - type: placeholder\n    value: \"%a%\"\n    expected: b"
        val error = assertFailsWith<IllegalArgumentException> { parse("a", text, placeholders = false) }
        assertTrue("PlaceholderAPI" in error.message!!, error.message)
        parse("a", text, placeholders = true)
    }

    @Test
    fun `names are lower case words that are not commands`() {
        assertFailsWith<IllegalArgumentException> { parse("Daily Reward", "name: A") }
        assertFailsWith<IllegalArgumentException> { parse("list", "name: A") }
        assertFailsWith<IllegalArgumentException> { parse("admin", "name: A") }
        parse("daily_reward-2", "name: A")
    }

    @Test
    fun `a file that is wrong is left out and the others load`() {
        val dir = Files.createTempDirectory("xaliredeem-vouchers")
        Files.writeString(dir.resolve("good.yml"), "name: Good")
        Files.writeString(dir.resolve("bad.yml"), "name: Bad\ncooldown: soon")
        Files.writeString(dir.resolve("broken.yml"), "name: [unclosed")
        Files.writeString(dir.resolve("notes.txt"), "not a voucher")
        val loaded = Vouchers.load(dir, placeholders = false)
        assertEquals(["good"], loaded.vouchers.keys.toList())
        assertEquals(2, loaded.errors.size)
        assertTrue(loaded.errors.any { it.startsWith("bad.yml") })
        assertTrue(loaded.errors.any { it.startsWith("broken.yml") })
    }

    @Test
    fun `placeholder conditions compare numbers as numbers and anything else as text`() {
        val compare = Condition.Placeholder.Companion::compare
        assertTrue(compare("1500.50", ">=", "1000"))
        assertTrue(compare("9", "<", "10"))
        assertFalse(compare("9", ">", "10"))
        assertTrue(compare("10.0", "==", "10"))
        assertTrue(compare("Vip", "==", "vip"))
        assertTrue(compare("Vip", "!=", "default"))
        assertTrue(compare("a,b,c", "contains", "b"))
        assertFalse(compare("abc", ">", "abb"))
        assertFalse(compare("abc", "~", "abc"))
    }
}
