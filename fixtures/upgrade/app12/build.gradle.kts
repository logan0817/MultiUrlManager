import java.util.Properties
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
val legacy = providers.gradleProperty("upgradeStage").getOrElse("current") == "legacy"
val stage = if (legacy) "legacy12" else "current12"
layout.buildDirectory.set(rootProject.layout.projectDirectory.dir("build/$stage/app"))
android {
    namespace = "com.logan.multiurlmanager.upgrade11"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.logan.multiurlmanager.upgrade12"
        minSdk = 24
        targetSdk = 36
        versionCode = if (legacy) 1 else 2
        versionName = stage
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("boolean", "LEGACY", legacy.toString())
    }
    buildFeatures { buildConfig = true }
    // 复用 1.1 快照升级行为测试；applicationId 独立，保留旧基线套件。
    sourceSets.getByName("main").manifest.srcFile("../app11/src/main/AndroidManifest.xml")
    sourceSets.getByName("androidTest").java.srcDir("../app11/src/androidTest/java")
    buildTypes {
        getByName("debug") { applicationIdSuffix = ".debug" }
        getByName("release") {
            applicationIdSuffix = ".release"
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = false
        }
    }
    testBuildType = providers.gradleProperty("upgradeVariant").getOrElse("debug")
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
val baseline = rootProject.file("../../build/compat-baseline/multiurlmanager-1.2.0.aar")
val verifyLegacyArtifact = tasks.register("verifyLegacyArtifact") {
    doLast {
        check(baseline.isFile) { "先执行 scripts/ci/check-api.py 取得已验证的 1.2.0 AAR" }
        val digest = MessageDigest.getInstance("SHA-256").digest(baseline.readBytes()).joinToString("") { "%02x".format(it) }
        check(digest == "a17a749232ccdeab14c0b209493f10a2281409e8c2d219457d0b5d095a7335b5") { "1.2.0 发行 AAR 摘要不符" }
    }
}
if (legacy) tasks.named("preBuild") { dependsOn(verifyLegacyArtifact) }
val version = Properties().apply {
    rootProject.file("../../gradle.properties").inputStream().use { load(it) }
}.getProperty("APP_VERSION_NAME")
dependencies {
    if (legacy) implementation(files(baseline))
    else implementation("io.github.logan0817:multiurlmanager:$version")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.google.code.gson:gson:2.11.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}
