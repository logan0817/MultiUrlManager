import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("com.android.library")
    id("kotlin-parcelize")
    kotlin("android")
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
    val groupProvider = project.provider { project.group.toString() }
    val versionProvider = project.provider { project.version.toString() }

    publishToMavenCentral(automaticRelease = true)
    // 2. 配置 GAV 坐标 (使用 Provider)
    coordinates(
        groupProvider.get(), // Group ID
        artifactId = "library-android", // Artifact ID
        versionProvider.get()
    )

    pom {
        name = "Flocon Datastores Integration"
        description = "A template for Kotlin Android projects"
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