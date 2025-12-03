plugins {
    id("com.android.application") apply false
    id("com.android.library") apply false
    kotlin("android") apply false
    cleanup
    base
}

allprojects {
    val GROUP: String by project
    val VERSION: String by project
    val USE_SNAPSHOT: String? by project
    group = GROUP
    version = if (USE_SNAPSHOT.toBoolean()) "$VERSION-SNAPSHOT" else VERSION
}
