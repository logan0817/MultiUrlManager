pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositories {
        maven { url = uri("../../build/ci/maven"); content { includeGroup("io.github.logan0817") } }
        google()
        mavenCentral()
    }
}
rootProject.name = "upgrade-consumer"
include(":app")
