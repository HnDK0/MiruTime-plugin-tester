import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.util.zip.ZipInputStream

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

// mirutime-core is not published to a public Maven repository. The CLI tool
// downloads the Maven repository attached to the MiruTime nightly release and
// falls back to the latest published release. `-PmirutimeCoreVersion=<v>`
// skips the download and resolves that version from mavenLocal() instead.
// `MIRUTIME_GITHUB_API` overrides the API base (used by the local mock test).
val coreRepoDir = rootDir.resolve("build/core-repo")
val coreVersionFile = rootDir.resolve("build/core-version.txt")
val githubApi = System.getenv("MIRUTIME_GITHUB_API") ?: "https://api.github.com"
val versionOverride = providers.gradleProperty("mirutimeCoreVersion").orNull?.takeIf { it.isNotBlank() }

fun rememberCoreVersion(version: String) {
    coreVersionFile.parentFile.mkdirs()
    coreVersionFile.writeText(version)
}

fun httpText(url: String, accept: String = "application/vnd.github+json"): String? {
    val connection = URI(url).toURL().openConnection() as HttpURLConnection
    connection.setRequestProperty("Accept", accept)
    connection.setRequestProperty("User-Agent", "mirutime-plugin-tester")
    System.getenv("GITHUB_TOKEN")?.let {
        connection.setRequestProperty("Authorization", "Bearer $it")
    }
    connection.connectTimeout = 15_000
    connection.readTimeout = 60_000
    return try {
        when (val code = connection.responseCode) {
            in 200..299 -> connection.inputStream.bufferedReader().use { it.readText() }
            404 -> null
            else -> error("mirutime-core GitHub API returned HTTP $code for $url")
        }
    } catch (e: java.io.IOException) {
        error("mirutime-core GitHub API request failed for $url: ${e.message}")
    } finally {
        connection.disconnect()
    }
}

fun coreZip(releaseUrl: String): Pair<String, String>? {
    val body = httpText(releaseUrl) ?: return null
    // GitHub returns assets in upload order; take the last match so a stale
    // asset left behind by a failed nightly cleanup never wins.
    val urls = Regex(""""browser_download_url"\s*:\s*"([^"]*mirutime-core-maven-[^"]*\.zip)"""")
        .findAll(body)
        .map { it.groupValues[1] }
        .toList()
    val url = urls.lastOrNull() ?: return null
    val version = url.substringAfterLast('/')
        .removePrefix("mirutime-core-maven-")
        .removeSuffix(".zip")
    return version to url
}

fun downloadCore(url: String, destination: File) {
    val connection = URI(url).toURL().openConnection() as HttpURLConnection
    connection.setRequestProperty("User-Agent", "mirutime-plugin-tester")
    connection.connectTimeout = 15_000
    connection.readTimeout = 300_000
    check(connection.responseCode in 200..299) {
        "mirutime-core download failed: HTTP ${connection.responseCode} from $url"
    }
    destination.deleteRecursively()
    destination.mkdirs()
    val root = destination.canonicalFile.toPath()
    connection.inputStream.use { input ->
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val target = destination.resolve(entry.name).canonicalFile
                check(target.toPath().startsWith(root)) { "unsafe zip entry: ${entry.name}" }
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile.mkdirs()
                    target.outputStream().use { zip.copyTo(it) }
                }
                zip.closeEntry()
            }
        }
    }
}

fun cachedCoreIsUsable(version: String): Boolean {
    val artifactDir = coreRepoDir.resolve(
        "com/github/HnDK0/MiruTime/mirutime-core-jvm/$version"
    )
    return coreRepoDir.isDirectory &&
        artifactDir.resolve("mirutime-core-jvm-$version.jar").isFile &&
        artifactDir.resolve("mirutime-core-jvm-$version.pom").isFile
}

val refresh = gradle.startParameter.isRefreshDependencies
val cachedVersion = coreVersionFile
    .takeIf { it.isFile }
    ?.readText()
    ?.trim()
    ?.takeIf { it.isNotEmpty() }

val resolvedCoreVersion: String = when {
    versionOverride != null -> versionOverride.also(::rememberCoreVersion)
    !refresh && cachedVersion != null && cachedCoreIsUsable(cachedVersion) -> cachedVersion
    else -> {
        val core = coreZip("$githubApi/repos/HnDK0/MiruTime/releases/tags/nightly")
            ?: coreZip("$githubApi/repos/HnDK0/MiruTime/releases/latest")
            ?: error(
                "No mirutime-core-maven-*.zip in the MiruTime nightly or latest release. " +
                    "Check https://github.com/HnDK0/MiruTime/releases, or pass " +
                    "-PmirutimeCoreVersion=<version> with a local publishToMavenLocal()."
            )
        val (version, url) = core
        downloadCore(url, coreRepoDir)
        rememberCoreVersion(version)
        println("mirutime-core $version downloaded from $url")
        version
    }
}

dependencyResolutionManagement {
    repositories {
        maven { url = uri(coreRepoDir) }
        mavenLocal()
        google()
        mavenCentral()
    }
}

rootProject.name = "mirutime-plugin-tester"
