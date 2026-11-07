// mirutime-plugin-tester: CLI-харнес для отладки Lua-плагинов NoveLA.
// Потребляет либу как Maven-артефакт из GitHub-релиза MiruTime, а не как
// project() — ядро остаётся чистым. Плоский JVM `application`:
// `gradle run --args` повторяет старый вызов.
plugins {
    kotlin("jvm") version "2.4.20"
    application
}

kotlin {
    // Тулчейн как у либы (jvmTarget 17, сборка на JDK 21) — одинаковая
    // проверка JVM-target у обеих сторон.
    jvmToolchain(21)
}

application {
    mainClass.set("dev.mirutime.tester.MainKt")
}

// Версию определяет settings.gradle.kts (nightly → latest release) и пишет
// её в build/core-version.txt; -PmirutimeCoreVersion переопределяет.
val coreVersion = layout.projectDirectory.file("build/core-version.txt").asFile.readText().trim()

dependencies {
    // JVM-вариант либы: api-зависимости (coroutines, ktor, serialization)
    // приходят транзитивно из Maven-репозитория, приложенного к релизу.
    implementation("com.github.HnDK0.MiruTime:mirutime-core-jvm:$coreVersion")
    // Ksoup для raw-HTML кросс-чеков (card/chapter/img counting) — у либы он
    // runtime-only, поэтому на compile-classpath тестера его нет.
    implementation("com.fleeksoft.ksoup:ksoup:0.2.6")
}
