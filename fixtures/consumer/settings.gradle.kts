pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        // 只从本轮 publication 解析库，不以源码工程依赖代替真实发布消费。
        maven {
            url = uri("../../build/ci/maven")
            content { includeGroup("io.github.logan0817") }
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "release-consumer"
include(":app")
