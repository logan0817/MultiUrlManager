import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("com.android.library")
    id("kotlin-parcelize")
    kotlin("android")
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
        disable.add("AndroidGradlePluginVersion")
    }
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// 使用根工程配置验证文档内的真实 JSON 示例，不复制一份容易过期的测试样例。
tasks.withType<Test>().configureEach {
    inputs.files(rootProject.file("README.md"), rootProject.file("README_EN.md"))
    systemProperty("multiurlmanager.projectRoot", rootProject.projectDir.absolutePath)
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

apply(plugin = "maven-publish")
group = "io.github.logan0817"
version = providers.gradleProperty("APP_VERSION_NAME").get()
// 发布同一份 Release AAR、源码和文档；普通 CI 只写仓库内的文件 Maven 仓库。
android.publishing {
    singleVariant("release") {
        withSourcesJar()
        withJavadocJar()
    }
}
afterEvaluate {
    extensions.configure<PublishingExtension> {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                artifactId = "multiurlmanager"
                pom {
                    name.set("MultiUrlManager")
                    description.set("Android multi-environment URL configuration manager")
                    url.set("https://github.com/logan0817/MultiUrlManager")
                    licenses {
                        license {
                            name.set("MIT License")
                            url.set("https://opensource.org/licenses/MIT")
                        }
                    }
                    developers {
                        developer {
                            id.set("logan0817")
                            name.set("Logan Gan")
                        }
                    }
                    scm {
                        url.set("https://github.com/logan0817/MultiUrlManager")
                        connection.set("scm:git:https://github.com/logan0817/MultiUrlManager.git")
                    }
                }
            }
        }
        repositories {
            maven {
                name = "Ci"
                url = uri(rootProject.layout.buildDirectory.dir("ci/maven"))
            }
        }
    }
}
