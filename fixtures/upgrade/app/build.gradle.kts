import java.util.Properties
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val legacy = providers.gradleProperty("upgradeStage").getOrElse("current") == "legacy"
val mode = if (legacy) "legacy" else "current"
layout.buildDirectory.set(rootProject.layout.projectDirectory.dir("build/$mode/app"))
android {
    namespace = "com.logan.multiurlmanager.upgrade"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.logan.multiurlmanager.upgrade"
        minSdk = 24
        targetSdk = 36
        versionCode = if (legacy) 1 else 2
        versionName = mode
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("boolean", "LEGACY", legacy.toString())
    }
    buildFeatures { buildConfig = true }
    buildTypes {
        getByName("debug") { applicationIdSuffix = ".debug" }
        getByName("release") {
            applicationIdSuffix = ".release"
            // 测试证书只用于独立夹具；两代证书相同才能执行保留数据的覆盖安装。
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = false
        }
    }
    testBuildType = providers.gradleProperty("upgradeVariant").getOrElse("debug")
    sourceSets.getByName("main").assets.srcDirs(if (legacy) "src/legacy/assets" else "../../../app/src/main/assets")
    if (!legacy) sourceSets.getByName("release").assets.srcDir("../../../app/src/release/assets")
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

val baseline = rootProject.file("../../build/compat-baseline/multiurlmanager-1.0.6.aar")
val verifyLegacyArtifact = tasks.register("verifyLegacyArtifact") {
    doLast {
        check(baseline.isFile) { "先执行 scripts/ci/check-api.py 取得已验证的 1.0.6 AAR" }
        val digest = MessageDigest.getInstance("SHA-256").digest(baseline.readBytes()).joinToString("") { "%02x".format(it) }
        check(digest == "cffa13b0bf3fb43c209621e135701d7925024ff83ca5354910ca8aafc790be9e") { "旧发行 AAR 摘要不符" }
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
