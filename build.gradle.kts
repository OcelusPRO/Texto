import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktor)
}

group = "fr.ftnl.texto"
version = "2.0.0"

application {
    mainClass.set("fr.ftnl.texto.ApplicationKt")
    applicationDefaultJvmArgs = listOf("-Djava.awt.headless=true")
}

repositories {
    mavenCentral()
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
        freeCompilerArgs.add("-Xannotation-default-target=param-property")
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

ktor {
    fatJar {
        archiveFileName.set("app.jar")
    }
}

dependencies {
    implementation(libs.bundles.ktor.server)
    implementation(libs.bundles.ktor.client)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.bundles.database)
    implementation(libs.logback.classic)
    implementation(libs.bouncycastle)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

// Copie le frontend compilé (web/dist) dans les ressources du jar quand il existe.
val webDist = layout.projectDirectory.dir("web/dist")
tasks.processResources {
    from(webDist) { into("web") }
    from(layout.projectDirectory.dir("cli")) { into("cli") }
}
