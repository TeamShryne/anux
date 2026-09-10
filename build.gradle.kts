// Top-level build file. Vendored :terminal-view / :terminal-emulator use legacy
// Groovy build files + AGP via buildscript (see termux-app upstream), so keep the
// buildscript block for them while modern modules use the plugins {} block.
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("com.android.tools.build:gradle:8.13.2")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.0.21")
        classpath("com.google.devtools.ksp:com.google.devtools.ksp.gradle.plugin:2.0.21-1.0.25")
    }
}

// NOTE: repositories are declared centrally in settings.gradle.kts
// (RepositoriesMode.FAIL_ON_PROJECT_REPOS), so no allprojects block here.

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
