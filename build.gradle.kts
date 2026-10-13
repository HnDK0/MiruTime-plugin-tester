// mirutime-plugin-tester: CLI-харнес для отладки Lua-плагинов NoveLA.
// Потребляет либу как опубликованный JVM-артефакт (mavenLocal), а не как
// project() — модуль вынесен из MiruTime-Core, ядро остаётся чистым.
// Плоский JVM `application`: `gradle run --args` повторяет старый вызов.
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

dependencies {
    // JVM-вариант либы: api-зависимости (coroutines, ktor, serialization)
    // приходят транзитивно. Версия — только в gradle/libs.versions.toml
    // (по умолчанию main-SNAPSHOT = последний коммит main через JitPack).
    implementation(libs.mirutime.core.jvm)
    // Ksoup для raw-HTML кросс-чеков (card/chapter/img counting) — у либы он
    // runtime-only, поэтому на compile-classpath тестера его нет.
    implementation("com.fleeksoft.ksoup:ksoup:0.2.6")
}
