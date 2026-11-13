plugins {
    kotlin("plugin.serialization")
    alias(libs.plugins.shadow)
}

dependencies {
    // Libraries the server downloads when the plugin loads (see loader.json); nothing declared here is shaded.
    api(libs.xalicraft.util)
    api(libs.bundles.database)
    api(libs.bundles.exposed)

    // Folia's API is Paper's plus the regionised scheduler, so it covers both platforms.
    compileOnly(libs.folia.api)
    compileOnly(libs.placeholderapi)
    // XaliMenu provides this at run time (its jar carries the API), when it is installed and `menu.enabled` is on.
    compileOnly(libs.xali.menu.api)
    testImplementation(libs.xalicraft.util)
    testImplementation(libs.folia.api)
    testImplementation(libs.bundles.database)
    testImplementation(libs.bundles.exposed)
}

tasks.test {
    useJUnitPlatform()
}

tasks {
    jar { enabled = false }
    shadowJar {
        minimize()
    }
    build {
        dependsOn(shadowJar)
    }
}
