// What other Paper plugins compile against to give vouchers or store ad-hoc rewards in XaliRedeem. The server provides it
// at run time from XaliRedeem's jar, so a plugin that uses it lists XaliRedeem as a soft or required dependency and does not
// bundle this.

plugins {
    `maven-publish`
}

dependencies {
    compileOnly(libs.paper.api)
    testImplementation(libs.paper.api)
}

tasks.test {
    useJUnitPlatform()
}
