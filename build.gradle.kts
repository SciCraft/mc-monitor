import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "2.3.0-RC3"
    id("com.gradleup.shadow") version "9.2.2"
    application
}

group = "net.scicraft"
version = "1.0.2"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("io.prometheus:simpleclient_httpserver:0.16.0")
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<KotlinCompile> {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_25)
    }
}

application {
    mainClass.set("net.scicraft.mc.monitor.MonitorKt")
}

tasks.shadowJar {
    archiveClassifier.set("")
}

tasks.jar {
    archiveClassifier.set("thin")
}
