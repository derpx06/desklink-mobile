pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
        maven {
            url = uri("https://jitpack.io")
            // JitPack builds from mutable git tags: only the groups that are really needed come from it
            // (the KSP class index and the Termux terminal), never a name another repository serves.
            content {
                includeGroup("com.github.albertvaka")
                includeGroup("com.github.termux.termux-app")
            }
        }
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven {
            url = uri("https://jitpack.io")
            // JitPack builds from mutable git tags: only the groups that are really needed come from it
            // (the KSP class index and the Termux terminal), never a name another repository serves.
            content {
                includeGroup("com.github.albertvaka")
                includeGroup("com.github.termux.termux-app")
            }
        }
    }
}

buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("org.ow2.asm:asm-util:9.6")
    }
}

rootProject.name = "desklink-mobile"
