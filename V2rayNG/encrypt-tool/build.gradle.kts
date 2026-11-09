plugins {
    alias(libs.plugins.kotlin.jvm)
    id("application")
}

application {
    mainClass = "SpeedUpVPN.VpnEncryptTool"
}

kotlin {
    jvmToolchain(17)
}
