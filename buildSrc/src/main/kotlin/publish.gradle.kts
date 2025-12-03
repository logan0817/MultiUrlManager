import com.android.build.gradle.LibraryExtension
import org.gradle.api.tasks.bundling.Jar

/**
 * Precompiled script plugin from:
 * https://github.com/logan0817/MultiUrlManager/blob/master/buildSrc/src/main/kotlin/publish.gradle.kts
 *
 * The following plugin tasks care of setting up:
 * - Publishing to Maven Central and Sonatype Snapshots
 * - GPG Signing with in memory PGP Keys
 * - Javadoc/SourceJar are attached via AGP
 *
 * To use it just apply:
 *
 * plugins {
 *     publish
 * }
 *
 * To your build.gradle.kts.
 *
 * If you copy over this file in your project, make sure to copy it inside: buildSrc/src/main/kotlin/publish.gradle.kts.
 * Make sure to copy over also buildSrc/build.gradle.kts otherwise this plugin will fail to compile due to missing dependencies.
 */
plugins {
    id("maven-publish")
    id("signing")
}

publishing {
    publications {
        create<MavenPublication>("release") {
            afterEvaluate {
                if (plugins.hasPlugin("com.android.library")) {
                    from(components["release"])
                } else {
                    from(components["java"])
                }
            }

            pom {
                description.set("A template for Kotlin Android projects")
                url.set("https://github.com/logan0817/MultiUrlManager/")

                licenses {
                    license {
                        name.set("The MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }
                developers {
                    developer {
                        id = "logan0817"
                        name ="logan"
                        email = "notwalnut@163.com"
                    }
                }
                scm {
                    connection.set("scm:git:git://github.com/logan0817/MultiUrlManager.git")
                    developerConnection.set("scm:git:ssh://github.com/logan0817/MultiUrlManager.git")
                    url.set("https://github.com/logan0817/MultiUrlManager/")
                }
                issueManagement {
                    system.set("GitHub Issues")
                    url.set("https://github.com/logan0817/MultiUrlManager/issues")
                }
            }
        }
    }

    val signingKey = "SIGNING_KEY".byProperty
    val signingPwd = "SIGNING_PWD".byProperty
    if (signingKey.isNullOrBlank() || signingPwd.isNullOrBlank()) {
        logger.info("Signing Disable as the PGP key was not found")
    } else {
        logger.info("GPG Key found - Signing enabled")
        signing {
            useInMemoryPgpKeys(signingKey, signingPwd)
            sign(publishing.publications["release"])
        }
    }
}


val String.byProperty: String? get() = findProperty(this) as? String
