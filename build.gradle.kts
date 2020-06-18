import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.3.72"
    id("com.github.johnrengelman.shadow") version "6.0.0"
}

group = "edu.jellymath"
version = "1.2"

repositories {
    mavenCentral()
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation("com.vk.api", "sdk", "1.0.6")
    implementation("org.telegram", "telegrambots", "4.9")
    implementation("com.github.salomonbrys.kotson", "kotson", "2.5.0")
}

tasks.withType<KotlinCompile>().configureEach {
    kotlinOptions.jvmTarget = "11"
}