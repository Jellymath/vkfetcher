import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

group = "edu.jellymath"
version = "1.0-SNAPSHOT"

buildscript {
    var kotlinVersion: String by extra
    kotlinVersion = "1.2.10"

    repositories {
        mavenCentral()
    }
    
    dependencies {
        classpath(kotlinModule("gradle-plugin", kotlinVersion))
    }
    
}

apply {
    plugin("kotlin")
}

val kotlinVersion: String by extra

repositories {
    mavenCentral()
}

dependencies {
    compile(kotlinModule("stdlib-jdk8", kotlinVersion))
    compile("com.vk.api", "sdk", "0.5.6")
    compile("org.telegram", "telegrambots", "3.5")
    compile("com.github.salomonbrys.kotson", "kotson", "2.5.0")
}

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "1.8"
}

