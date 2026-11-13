import mc.xalitoria.gradle.Load
import mc.xalitoria.gradle.RelativeLoad

plugins {
    kotlin("jvm")
    id("mc.xalitoria.conventions")
    id("mc.xalitoria.minecraft")
    alias(libs.plugins.shadow) apply false
}

subprojects {
    // Named for the package of what the module holds, see the Packages section of the root README.
    group = when (name) {
        "paper" -> "mc.xalitoria.redeem.paper"
        else -> error("No group for $path")
    }
    version = "0.2.0"
}

tasks.named("build") {
    dependsOn(subprojects.map { "${it.path}:build" })
}

paper {
    name = "XaliRedeem"
    main = "mc.xalitoria.redeem.paper.XaliRedeem"
    prefix = "XaliRedeem"
    author = "audizian"
    website = "https://xalitoria.com"
    description = "Vouchers redeemed from a menu or a command, with conditions such as inventory space and permissions."
    apiVersion = "26.2"
    load = Load.POSTWORLD
    foliaSupported = true
    relocateSources = true
    serverDependencies {
        register("PlaceholderAPI") { load = RelativeLoad.BEFORE; required = false }
        // The menu's actions and catalog are registered while this plugin enables, so XaliMenu has to have enabled.
        register("XaliMenu") { load = RelativeLoad.BEFORE; required = false }
    }
}
