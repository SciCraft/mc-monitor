import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.6.21"
    id("com.github.johnrengelman.shadow") version "5.2.0"
    application
}

group = "net.scicraft"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.4.1")
    implementation("io.prometheus:simpleclient_httpserver:0.16.0")
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "1.8"
}

application {
    mainClassName = "net.scicraft.mc.monitor.MonitorKt"
}

tasks.shadowJar {
    archiveClassifier.set("")
}