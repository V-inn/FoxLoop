pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // FoxLoop Console's terminal comes from Termux's terminal-emulator and
        // terminal-view modules, which are published nowhere but JitPack. The
        // content filter is the point: JitPack builds whatever a tag points at,
        // so it is allowed to answer for that one group and nothing else, and
        // can never shadow a Maven Central artifact.
        maven("https://jitpack.io") {
            content { includeGroup("com.github.termux.termux-app") }
        }
    }
}

rootProject.name = "foxloop-client"
include(":app")
