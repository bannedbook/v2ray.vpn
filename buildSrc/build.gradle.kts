plugins {
    `java-gradle-plugin`
    `kotlin-dsl`
}

apply(from = "../repositories.gradle.kts")

dependencies {
    // Gradle Plugins
    implementation("com.android.tools.build:gradle:8.1.3")
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:1.8.10")
}
