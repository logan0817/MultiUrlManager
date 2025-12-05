import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("com.android.library")
    id("kotlin-parcelize")
    kotlin("android")
    id("signing")
    alias(libs.plugins.vanniktech.maven.publish)
}


android {
    compileSdk = libs.versions.compile.sdk.version.get().toInt()

    buildFeatures {
        buildConfig = true
    }

    defaultConfig {
        minSdk = libs.versions.min.sdk.version.get().toInt()
        namespace = "com.logan.multiurlmanager.library"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }


    lint {
        warningsAsErrors = true
        abortOnError = true
        disable.add("GradleDependency")
        // 忽略 AGP 版本检查，因为我们受限于 CI 环境
        disable.add("AndroidGradlePluginVersion")
    }
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.recyclerview)
    implementation(libs.google.gson)
    testImplementation(libs.junit)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}


mavenPublishing {

    // 1. 定义安全的属性读取 Provider
    val GROUP: String by project
    val VERSION: String by project
    val USE_SNAPSHOT: String? by project

    publishToMavenCentral(automaticRelease = true)
    // 2. 配置 GAV 坐标 (使用 Provider)
    coordinates(
        GROUP,
        artifactId = "multiurlmanager",
        if (USE_SNAPSHOT.toBoolean()) "$VERSION-SNAPSHOT" else VERSION
    )

    pom {
        name = "Multi URL Manager Android Library"
        description = "A comprehensive library for managing multiple URL configurations in Android applications."
        inceptionYear = "2025"
        url = "https://github.com/logan0817/MultiUrlManager/"

        licenses {
            license {
                name = "The Apache License, Version 2.0"
                url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                distribution = "https://www.apache.org/licenses/LICENSE-2.0.txt"
            }
        }
        developers {
            developer {
                id = "logan0817"
                name = "logan"
                url = "https://github.com/logan0817"
                email = "notwalnut@163.com"
            }
        }
        scm {
            connection = "scm:git:git://github.com/logan0817/MultiUrlManager.git"
            developerConnection =
                "scm:git:ssh://github.com/logan0817/MultiUrlManager.git"
            url = "https://github.com/logan0817/MultiUrlManager/"
        }
        issueManagement {
            system = "GitHub Issues"
            url = "https://github.com/logan0817/MultiUrlManager/issues"
        }
    }
}

signing {
    // 1. 读取由 GitHub Actions 传入的属性
    val signingKeyId = project.findProperty("signingKeyId") as String?
    val signingPassword = project.findProperty("signingPassword") as String?
    val signingKey = project.findProperty("signingKey") as String?
    val signingKeyPlaceholder = project.findProperty("signingKeyPlaceholder") as String? // 读取占位符

    val actualSigningKey = if (signingKey.isNullOrEmpty() || signingKeyPlaceholder.isNullOrEmpty()) {
        signingKey // 如果没有占位符，则使用原始密钥 (本地环境)
    } else {
        // 关键：将占位符 '§' 替换回换行符 '\n'，恢复多行私钥
        signingKey.replace(signingKeyPlaceholder, "\n")
    }

    // 2. 只有在所有 GPG 签名属性都存在时，才启用 useInMemoryPgpKeys
    if (!signingKeyId.isNullOrEmpty() && !signingPassword.isNullOrEmpty() && !actualSigningKey.isNullOrEmpty()) {
        logger.lifecycle("✅ GPG Configuration Found: Using in-memory keys for CI/CD signing.")
        useInMemoryPgpKeys(signingKeyId, actualSigningKey, signingPassword)
    } else {
        logger.lifecycle("⚠️ GPG Configuration Missing: Signing will likely be skipped or fail in CI/CD.")
        // 在 CI 环境中，强制在没有密钥时失败
        if (System.getenv("CI") == "true") {
            throw IllegalStateException("GitHub Actions failed to inject GPG signing properties (signingKeyId, signingKey, signingPassword). Cannot perform publication.")
        }
    }

    // 3. 告诉 signing 插件为所有 Publication 签名
    sign(publishing.publications)
}