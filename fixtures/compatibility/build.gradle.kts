import java.util.zip.ZipFile
import java.security.MessageDigest

plugins {
    kotlin("jvm") version "1.9.23"
    application
}

val compileVersion = providers.gradleProperty("compatBaseline").getOrElse("1.1.0")
layout.buildDirectory.set(layout.projectDirectory.dir("build/$compileVersion"))
val baselineVersion = if (compileVersion == "1.1.0") "1.1.0" else "1.2.0"
val published = file("../../build/compat-baseline/multiurlmanager-$baselineVersion.aar")
val networkPublished = file("../../build/compat-baseline/multiurlmanager-okhttp-1.2.0.aar")
val networkCurrent = file("../../library-okhttp/build/outputs/aar/library-okhttp-release.aar")
val current = file("../../library-android/build/outputs/aar/library-android-release.aar")
val jars = layout.buildDirectory.dir("jars")
val extractAars = tasks.register("extractAars") {
    inputs.files(published, current)
    if (baselineVersion == "1.2.0") inputs.files(networkPublished, networkCurrent)
    outputs.dir(jars)
    doLast {
        check(compileVersion in listOf("1.1.0", "1.2.0", "current"))
        check(MessageDigest.getInstance("SHA-256").digest(published.readBytes())
            .joinToString("") { "%02x".format(it) } == if (baselineVersion == "1.1.0") "d4237ddba47f663b2c73f80f3a18e380fdea0b62d87ab5eda10813ca39a6e897"
            else "a17a749232ccdeab14c0b209493f10a2281409e8c2d219457d0b5d095a7335b5")
        jars.get().asFile.mkdirs()
        val artifacts = mutableListOf(baselineVersion to published, "current" to current)
        if (baselineVersion == "1.2.0") {
            check(MessageDigest.getInstance("SHA-256").digest(networkPublished.readBytes())
                .joinToString("") { "%02x".format(it) } == "de6a423019da48c6bb0b2b8221627ad6b8da78f4ea82e3afc76c45d8c017da13")
            artifacts += listOf("network-1.2.0" to networkPublished, "network-current" to networkCurrent)
        }
        artifacts.forEach { (name, aar) ->
            ZipFile(aar).use { archive ->
                archive.getInputStream(archive.getEntry("classes.jar")).use { input ->
                    jars.get().file("$name.jar").asFile.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
    }
}
val sdk = providers.environmentVariable("ANDROID_HOME")
    .orElse(providers.environmentVariable("ANDROID_SDK_ROOT"))
val androidJar = file("${sdk.get()}/platforms/android-36/android.jar")
check(androidJar.isFile) { "需要已安装的 Android 36 android.jar" }
dependencies {
    compileOnly(files(jars.map { it.file("$compileVersion.jar") }))
    compileOnly(files(androidJar))
    runtimeOnly(files(jars.map { it.file("current.jar") }))
    runtimeOnly(files(androidJar))
    if (baselineVersion == "1.2.0") {
        compileOnly(files(jars.map { it.file("network-$compileVersion.jar") }))
        runtimeOnly(files(jars.map { it.file("network-current.jar") }))
        implementation("com.squareup.okhttp3:okhttp:4.12.0")
    }
}
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach { dependsOn(extractAars) }
tasks.withType<JavaCompile>().configureEach { dependsOn(extractAars) }
kotlin { jvmToolchain(17) }
if (baselineVersion == "1.2.0") sourceSets.main {
    java.srcDir("src/v12/java")
    kotlin.srcDir("src/v12/kotlin")
}
application {
    mainClass.set(if (baselineVersion == "1.2.0") "com.logan.multiurlmanager.compatibility.Compatibility12Kt"
        else "com.logan.multiurlmanager.compatibility.CompatibilityKt")
}
