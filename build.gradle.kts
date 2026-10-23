plugins {
    id("com.android.application") version "8.13.2" apply false
    kotlin("android") version "2.2.21" apply false
    kotlin("jvm") version "2.2.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.21" apply false
    id("com.google.devtools.ksp") version "2.2.21-2.0.5" apply false
}
subprojects { layout.buildDirectory.set(rootProject.layout.projectDirectory.dir("build/output/${project.name}")) }
layout.buildDirectory.set(layout.projectDirectory.dir("build/output/root"))
