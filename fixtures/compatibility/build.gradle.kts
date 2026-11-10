import java.util.zip.ZipFile
import java.security.MessageDigest

plugins {
    kotlin("jvm") version "1.9.23"
    application
}

val compileVersion = providers.gradleProperty("compatBaseline").getOrElse("1.1.0")
layout.buildDirectory.set(layout.projectDirectory.dir("build/$compileVersion"))
val published = file("../../build/compat-baseline/multiurlmanager-1.1.0.aar")
val current = file("../../library-android/build/outputs/aar/library-android-release.aar")
val jars = layout.buildDirectory.dir("jars")
val extractAars = tasks.register("extractAars") {
    inputs.files(published, current)
    outputs.dir(jars)
    doLast {
        check(compileVersion in listOf("1.1.0", "current"))
        check(MessageDigest.getInstance("SHA-256").digest(published.readBytes())
            .joinToString("") { "%02x".format(it) } == "d4237ddba47f663b2c73f80f3a18e380fdea0b62d87ab5eda10813ca39a6e897")
        jars.get().asFile.mkdirs()
        listOf("1.1.0" to published, "current" to current).forEach { (name, aar) ->
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
}
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach { dependsOn(extractAars) }
tasks.withType<JavaCompile>().configureEach { dependsOn(extractAars) }
kotlin { jvmToolchain(17) }
application { mainClass.set("com.logan.multiurlmanager.compatibility.CompatibilityKt") }
