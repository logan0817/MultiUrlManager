import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("com.android.library")
    kotlin("android")
    `maven-publish`
}

android {
    namespace = "com.logan.multiurlmanager.okhttp"
    compileSdk = libs.versions.compile.sdk.version.get().toInt()

    defaultConfig {
        minSdk = libs.versions.min.sdk.version.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint {
        warningsAsErrors = true
        abortOnError = true
        disable.add("GradleDependency")
        disable.add("AndroidGradlePluginVersion")
    }
    publishing {
        singleVariant("release") {
            withSourcesJar()
            withJavadocJar()
        }
    }
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    api(project(":library-android"))
    api(libs.okhttp)
    testImplementation(libs.junit)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.retrofit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.mockwebserver)
}

group = "io.github.logan0817"
version = providers.gradleProperty("APP_VERSION_NAME").get()
afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                artifactId = "multiurlmanager-okhttp"
                pom {
                    name.set("MultiUrlManager OkHttp")
                    description.set("Optional explicit business URL routing for MultiUrlManager")
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
