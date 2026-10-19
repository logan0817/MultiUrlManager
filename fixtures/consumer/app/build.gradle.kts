import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 从 README 原文提取接入示例，随真实 AAR 消费一起编译，避免文档与公开 API 漂移。
val readmeFiles = listOf(rootProject.file("../../README.md"), rootProject.file("../../README_EN.md"))
val readmeSources = layout.buildDirectory.dir("generated/readme/kotlin")
val compileReadmeInputs = tasks.register("prepareReadmeExamples") {
    inputs.files(readmeFiles)
    outputs.dir(readmeSources)
    doLast {
        val directory = readmeSources.get().asFile
        directory.deleteRecursively()
        readmeFiles.forEachIndexed { language, readme ->
            val examples = Regex("(?ms)^```kotlin\\s*\\n(.*?)^```\\s*$")
                .findAll(readme.readText()).map { it.groupValues[1] }
                .filter { it.contains("import com.logan.multiurlmanager.library.") }.toList()
            check(examples.size == 5) { "${readme.name}: expected five complete Kotlin usage examples" }
            examples.forEachIndexed { index, source ->
                // 中英文示例使用隔离包名，保留原调用代码，防止同名示例类互相冲突。
                val packageName = "com.example.readme.language$language"
                val code = if (source.startsWith("package ")) {
                    source.replaceFirst(Regex("^package [^\\n]+"), "package $packageName")
                } else {
                    "package $packageName\n\n$source"
                }
                directory.resolve("language$language/Example$index.kt").apply {
                    parentFile.mkdirs()
                    writeText(code)
                }
            }
        }
    }
}

android {
    namespace = "com.logan.multiurlmanager.consumer"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.logan.multiurlmanager.consumer"
        minSdk = 24
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    sourceSets.getByName("main").java.srcDir(readmeSources)
    buildTypes {
        create("minified") {
            initWith(getByName("release"))
            isMinifyEnabled = true
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "consumer.pro")
        }
    }
    testBuildType = "minified"
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

tasks.named("preBuild") {
    dependsOn(compileReadmeInputs)
}

val libraryVersion = providers.gradleProperty("libraryVersion").getOrElse(
    Properties().apply {
        rootProject.file("../../gradle.properties").inputStream().use { load(it) }
    }.getProperty("APP_VERSION_NAME")
)

dependencies {
    implementation("io.github.logan0817:multiurlmanager:$libraryVersion")
    // 业务宿主直接使用的 AppCompat 类型显式声明，不依赖库 implementation 的传递编译可见性。
    implementation("androidx.appcompat:appcompat:1.7.1")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}
