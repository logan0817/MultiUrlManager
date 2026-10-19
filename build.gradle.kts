import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication

plugins {
    id("com.android.application") apply false
    id("com.android.library") apply false
    kotlin("android") apply false
    base
}

val releaseVersion = providers.gradleProperty("APP_VERSION_NAME")
tasks.register("verifyCentralDeploymentName") {
    group = "verification"
    description = "离线核对实际 publication 坐标和 Central 项目批次名；不签名、不上传。"
    doLast {
        val publications = project(":library-android").extensions.getByType<PublishingExtension>()
            .publications.withType<MavenPublication>().toList()
        check(publications.size == 1 && publications.single().let {
            it.groupId == "io.github.logan0817" && it.artifactId == "multiurlmanager" &&
                it.version == releaseVersion.get()
        }) { "实际 publication 坐标必须为 io.github.logan0817:multiurlmanager:${releaseVersion.get()}" }
        logger.lifecycle("Central batch: io.github.logan0817-MultiUrlManager-${releaseVersion.get()}")
    }
}

tasks.register<Exec>("prepareCentralBundle") {
    group = "publishing"
    description = "对已封存的正式候选生成本机 GPG 签名包并验签；不读取 Central token、不上传。"
    dependsOn("verifyCentralDeploymentName")
    workingDir(rootDir)
    doFirst {
        check(!gradle.startParameter.isConfigurationCacheRequested) {
            "本机签名入口请使用 --no-configuration-cache。"
        }
        val version = releaseVersion.get()
        check(Regex("\\d+\\.\\d+\\.\\d+").matches(version)) { "签名包只接受正式版本，不能为 SNAPSHOT。" }
        // 复用另外两个项目使用的标准 GPG 属性；私钥始终留在本机 keyring。
        val fingerprint = providers.environmentVariable("SIGNING_FINGERPRINT")
            .orElse(providers.gradleProperty("signing.gnupg.keyName")).orNull
        check(!fingerprint.isNullOrBlank()) { "请设置 signing.gnupg.keyName 或 SIGNING_FINGERPRINT 完整指纹。" }
        environment("SIGNING_FINGERPRINT", fingerprint)
        environment("GPG_EXECUTABLE", providers.gradleProperty("signing.gnupg.executable")
            .orElse(providers.environmentVariable("GPG_EXECUTABLE")).getOrElse("gpg"))
        providers.gradleProperty("signing.gnupg.homeDir").orNull?.let { environment("GNUPGHOME", file(it).absolutePath) }
        commandLine("python3", "scripts/ci/publish-central.py", "--prepare-only",
            "--candidate", file(providers.gradleProperty("centralCandidateDir").getOrElse("build/ci/candidate")).absolutePath,
            "--version", version, "--output", layout.buildDirectory.file("central/multiurlmanager-$version.zip").get().asFile.absolutePath)
    }
}
