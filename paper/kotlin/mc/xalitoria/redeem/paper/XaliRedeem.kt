package mc.xalitoria.redeem.paper

import com.xalitoria.util.bukkit.coroutineScope
import com.xalitoria.util.bukkit.register
import com.xalitoria.util.config.YamlFile
import com.xalitoria.util.message.*
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import mc.xalitoria.redeem.paper.command.RedeemCommand
import mc.xalitoria.redeem.paper.data.config.MainConfig
import mc.xalitoria.redeem.paper.data.config.MessageConfig
import mc.xalitoria.redeem.paper.data.constants.Permissions
import mc.xalitoria.redeem.paper.data.db.DatabaseManager
import mc.xalitoria.redeem.paper.external.MenuHook
import mc.xalitoria.redeem.paper.external.RedeemExpansion
import mc.xalitoria.redeem.paper.listener.PlayerListener
import mc.xalitoria.redeem.paper.service.RedeemService
import mc.xalitoria.redeem.paper.voucher.Vouchers
import org.bukkit.permissions.Permission
import org.bukkit.permissions.PermissionDefault
import org.bukkit.plugin.java.JavaPlugin

class XaliRedeem : JavaPlugin() {
    lateinit var config: YamlFile<MainConfig>
        private set

    lateinit var messageConfig: YamlFile<MessageConfig>
        private set

    lateinit var scope: CoroutineScope
        private set

    lateinit var service: RedeemService
        private set

    /** Whether PlaceholderAPI is installed, which rewards, conditions and the menu's text can use. */
    var hasPlaceholders = false
        private set

    /** XaliMenu's side, when `menu.enabled` is on and XaliMenu is installed. */
    var menu: MenuHook? = null
        private set

    private var expansion: RedeemExpansion? = null

    override fun onEnable() {
        scope = coroutineScope()

        try {
            config = loadConfig()
            messageConfig = loadMessages(config.data)
        } catch (e: Exception) {
            logger.fail("The configuration is wrong; disabling.", e)
            server.pluginManager.disablePlugin(this)
            return
        }
        applyDebug()

        val pm = server.pluginManager
        try {
            DatabaseManager.connect(this, config.data.database)
        } catch (e: Exception) {
            logger.fail("Failed to connect to the database; disabling.", e)
            pm.disablePlugin(this)
            return
        }
        debug("Connected to the {0} database", config.data.database.type)

        // Everyone may redeem unless a permissions plugin says otherwise; each voucher's conditions have the say on who gets what.
        pm.addPermission(Permission(Permissions.USE, PermissionDefault.TRUE))
        pm.addPermission(Permission(Permissions.ADMIN, PermissionDefault.OP))

        hasPlaceholders = pm.isPluginEnabled("PlaceholderAPI")
        service = RedeemService(this)
        loadVouchers()
        pm.registerEvents(PlayerListener(this), this)
        // Players already online (a plugin reload) have their vouchers read now.
        server.onlinePlayers.forEach { player -> scope.launch { runCatching { service.load(player.uniqueId) } } }

        if (hasPlaceholders) expansion = RedeemExpansion(this).also { it.register() }
        enableMenu()

        val command = RedeemCommand(this)
        lifecycleManager.registerEventHandler(LifecycleEvents.COMMANDS) { event ->
            event.registrar().register(this, command.builder().build(), command.description, command.aliases)
        }
        debug("Enabled: menu={0}, placeholders={1}", menu != null, hasPlaceholders)
    }

    override fun onDisable() {
        expansion?.unregister()
        if (::scope.isInitialized) scope.cancel("XaliRedeem is disabling")
        DatabaseManager.close()
    }

    /**
     * Reads the configuration, the language file and the vouchers again, and returns how many vouchers there are. If the
     * configuration or the language file is wrong the old ones stay in use and this throws, saying what is wrong; a voucher
     * that is wrong is left out and reported, like at the start.
     */
    fun reload(): Int {
        val newConfig = loadConfig()
        val newMessages = loadMessages(newConfig.data)
        config = newConfig
        messageConfig = newMessages
        applyDebug()
        enableMenu()
        val count = loadVouchers()
        debug("Reloaded")
        return count
    }

    /** Reads the vouchers folder, writing the example into it on a first start; the number of vouchers that loaded. */
    private fun loadVouchers(): Int {
        val directory = dataPath.resolve("vouchers")
        Vouchers.writeExample(directory)
        val loaded = Vouchers.load(directory, hasPlaceholders)
        loaded.errors.forEach { logger fail messageConfig.data.commands.voucherError(it) }
        service.vouchers = loaded.vouchers
        if (loaded.vouchers.isEmpty()) logger.warning("There are no vouchers: add a file to plugins/${pluginMeta.name}/vouchers and run /${RedeemCommand.LABEL} admin reload")
        else debug("Vouchers: {0}", loaded.vouchers.keys.joinToString())
        return loaded.vouchers.size
    }

    /**
     * Connects to XaliMenu when `menu.enabled` is on and it is installed, and writes the menu into its folder if it has none
     * yet. Turning the option off stops the menu from being opened; XaliMenu keeps what this added until the server restarts.
     */
    private fun enableMenu() {
        val settings = config.data.menu
        if (!settings.enabled) {
            if (menu != null) logger.info("menu.enabled is off: /redeem lists the vouchers in chat. XaliMenu keeps the menu until the server restarts.")
            return
        }
        if (menu != null) return
        if (!server.pluginManager.isPluginEnabled("XaliMenu")) {
            logger.info("menu.enabled is on, but XaliMenu is not installed: /redeem lists the vouchers in chat instead")
            return
        }
        try {
            val hook = MenuHook(this)
            menu = hook
            if (settings.writeMenu && hook.writeMenu()) logger.info("The voucher menu is plugins/XaliMenu/menus/${settings.name}.conf; /xmenu reload reads it if XaliMenu has already started")
        } catch (e: Exception) {
            logger.fail("Failed to connect to XaliMenu; /redeem lists the vouchers in chat instead", e)
        }
    }

    private fun loadConfig(): YamlFile<MainConfig> {
        val file = YamlFile(dataPath.resolve("config.yml"), MainConfig.serializer(), ::MainConfig)
        file.load()
        file.data.validate()
        // Written again so a setting added by an update shows up in the file.
        file.save()
        return file
    }

    private fun loadMessages(config: MainConfig): YamlFile<MessageConfig> {
        val file = YamlFile(dataPath.resolve("language", config.language + ".yml"), MessageConfig.serializer(), ::MessageConfig)
        file.load()
        file.save()
        return file
    }

    /** Starts from `debug` in the configuration. */
    private fun applyDebug() {
        debugOn = config.data.debug
        updateDebugger()
    }

    /** Turns the debug log on or off until the next reload, and returns whether it is on. */
    fun toggleDebug(): Boolean {
        debugOn = !debugOn
        updateDebugger()
        return debugOn
    }

    private fun updateDebugger() {
        debugger =
            if (debugOn) { message: String, args: Array<out Any?> -> logger.info(message.formatArgs(*args)) }
            else { _: String, _: Array<out Any?> -> }
    }

    private var debugOn = false

    companion object {
        private var debugger: (String, Array<out Any?>) -> Unit = { _, _ -> }
        fun debug(message: String, vararg args: Any?) = debugger(message, args)
    }
}
