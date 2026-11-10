import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 从文档原文提取完整示例，随真实 AAR 消费编译，包含固定模式与实际网络请求页面。
val readmeExamples = listOf(
    rootProject.file("../../README.md") to 6,
    rootProject.file("../../README_EN.md") to 6,
    rootProject.file("../../docs/network-routing.md") to 1
)
val readmeSources = layout.buildDirectory.dir("generated/readme/kotlin")
val compileReadmeInputs = tasks.register("prepareReadmeExamples") {
    inputs.files(readmeExamples.map { it.first })
    outputs.dir(readmeSources)
    doLast {
        val directory = readmeSources.get().asFile
        directory.deleteRecursively()
        readmeExamples.forEachIndexed { language, (readme, expectedCount) ->
            val examples = Regex("(?ms)^```kotlin\\s*\\n(.*?)^```\\s*$")
                .findAll(readme.readText()).map { it.groupValues[1] }
                .filter { it.contains("import com.logan.multiurlmanager.library.") }.toList()
            check(examples.size == expectedCount) { "${readme.name}: expected $expectedCount complete Kotlin usage examples" }
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
                    // 与示例包名一起迁移宿主 BuildConfig，使用消费者真实生成的构建标识。
                    writeText(code.replace("import com.example.demo.BuildConfig", "import ${android.namespace}.BuildConfig"))
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
        versionCode = 1
        versionName = "verification"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    sourceSets.getByName("main").java.srcDir(readmeSources)
    buildFeatures {
        buildConfig = true
    }
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
    implementation("io.github.logan0817:multiurlmanager-okhttp:$libraryVersion")
    // 业务宿主直接使用的 AppCompat 类型显式声明，不依赖库 implementation 的传递编译可见性。
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}
