pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositories {
        // dev: локальная публикация либы (`:mirutime-core:publishToMavenLocal`
        // с -Pgroup=com.github.HnDK0.MiruTime-Core -Pversion=<та же версия,
        // что в gradle/libs.versions.toml>).
        mavenLocal()
        google()
        mavenCentral()
        // JitPack: отдаёт main-SNAPSHOT (последний коммит main) и теги либы.
        maven("https://jitpack.io")
    }
}

rootProject.name = "mirutime-plugin-tester"
