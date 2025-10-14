plugins {
    alias(libs.plugins.kotlin.jvm)
}

group = "fr.ftnl.texto"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.ksp.api)
}

tasks.test {
    useJUnitPlatform()
}
