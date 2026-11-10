import java.util.Properties
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
val legacy = providers.gradleProperty("upgradeStage").getOrElse("current") == "legacy"
val stage = if (legacy) "legacy11" else "current11"
layout.buildDirectory.set(rootProject.layout.projectDirectory.dir("build/$stage/app"))
android {
    namespace = "com.logan.multiurlmanager.upgrade11"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.logan.multiurlmanager.upgrade11"
        minSdk = 24
        targetSdk = 36
        versionCode = if (legacy) 1 else 2
        versionName = stage
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("boolean", "LEGACY", legacy.toString())
    }
    buildFeatures { buildConfig = true }
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
val baseline = rootProject.file("../../build/compat-baseline/multiurlmanager-1.1.0.aar")
val verifyLegacyArtifact = tasks.register("verifyLegacyArtifact") {
    doLast {
        check(baseline.isFile) { "先执行 scripts/ci/check-api.py 取得已验证的 1.1.0 AAR" }
        val digest = MessageDigest.getInstance("SHA-256").digest(baseline.readBytes()).joinToString("") { "%02x".format(it) }
        check(digest == "d4237ddba47f663b2c73f80f3a18e380fdea0b62d87ab5eda10813ca39a6e897") { "1.1.0 发行 AAR 摘要不符" }
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
