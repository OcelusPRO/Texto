import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

val ktor_version: String by project
val kotlin_version: String by project
val logback_version: String by project

plugins {
    kotlin("jvm") version "2.0.21"
    id("io.ktor.plugin") version "3.0.1"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21"
}

group = "fr.ftnl.texto"
version = "1.0.0"
var mainClassName = "io.ktor.server.netty.EngineMain"

application {
    mainClass.set("io.ktor.server.netty.EngineMain")

    val isDevelopment: Boolean = project.ext.has("development")
    applicationDefaultJvmArgs = listOf("-Dio.ktor.development=$isDevelopment")
}

repositories {
    mavenCentral()
    maven { url = uri("https://packages.confluent.io/maven/") }
    maven { url = uri("https://jitpack.io") }
}

dependencies {
    implementation("io.github.flaxoos:ktor-server-rate-limiting-jvm:2.0.0")
    implementation("io.github.flaxoos:ktor-server-task-scheduling-core-jvm:2.0.0")
    implementation("io.github.flaxoos:ktor-server-task-scheduling-redis-jvm:2.0.0")
    implementation("io.github.flaxoos:ktor-server-task-scheduling-mongodb-jvm:2.0.0")
    implementation("io.github.flaxoos:ktor-server-task-scheduling-jdbc-jvm:2.0.0")
    implementation("io.insert-koin:koin-ktor:3.5.6")
    implementation("io.insert-koin:koin-logger-slf4j:3.5.6")
    implementation("io.ktor:ktor-server-core-jvm")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm")
    implementation("io.ktor:ktor-server-content-negotiation-jvm")
    implementation("org.jetbrains.exposed:exposed-core:0.53.0")
    implementation("org.jetbrains.exposed:exposed-jdbc:0.53.0")
    implementation("io.ktor:ktor-server-mustache-jvm")
    implementation("io.ktor:ktor-serialization-gson-jvm")
    implementation("io.ktor:ktor-server-call-logging-jvm")
    implementation("dev.hayden:khealth:3.0.0")
    implementation("io.ktor:ktor-server-http-redirect-jvm")
    implementation("io.ktor:ktor-server-hsts-jvm")
    implementation("io.ktor:ktor-server-default-headers-jvm")
    implementation("io.ktor:ktor-server-compression-jvm")
    implementation("io.ktor:ktor-server-caching-headers-jvm")
    implementation("io.ktor:ktor-server-host-common-jvm")
    implementation("io.ktor:ktor-server-status-pages-jvm")
    implementation("io.ktor:ktor-server-double-receive-jvm")
    implementation("io.ktor:ktor-server-auto-head-response-jvm")
    implementation("io.ktor:ktor-server-sessions-jvm")
    implementation("io.ktor:ktor-server-auth-jvm")
    implementation("io.ktor:ktor-client-core-jvm")
    implementation("io.ktor:ktor-client-apache-jvm")
    implementation("io.ktor:ktor-server-netty-jvm")
    implementation("ch.qos.logback:logback-classic:$logback_version")
    implementation("io.ktor:ktor-server-config-yaml")


    testImplementation("io.ktor:ktor-server-test-host-jvm")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:$kotlin_version")
}


tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "18"
}

tasks.withType<ShadowJar> {
    archiveBaseName.set("api")
    archiveClassifier.set("")
    archiveFileName.set("app.jar")
}


tasks.withType<org.gradle.jvm.tasks.Jar> {
    manifest {
        attributes["Implementation-Title"] = "api"
        attributes["Implementation-Version"] = version
        attributes["Main-Class"] = mainClassName
    }
}