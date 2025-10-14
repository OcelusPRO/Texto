plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.shadow)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktor)
    alias(libs.plugins.ksp)
}

group = "fr.ftnl.texto"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    val ktor = libs.bundles.ktor
    implementation(ktor.server)
    implementation(ktor.client)
    implementation(ktor.mutuals)
    
    implementation(libs.bundles.database)
    implementation(libs.bundles.logging)
    
    ksp(project(":processor"))
}

sourceSets.main {
    kotlin.srcDir("build/generated/ksp/main/kotlin")
}
