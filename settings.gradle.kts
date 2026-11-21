pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == "com.android.application" || requested.id.id == "com.android.library") {
                useModule("com.android.tools.build:gradle:${requested.version}")
            }
        }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Official BOOX / Onyx SDK (EpdController, TouchHelper). The repo is HTTP-only, so it is
        // restricted to Onyx's own group: every other (transitive) artifact must come from the
        // HTTPS repos above.
        maven("http://repo.boox.com/repository/maven-public/") {
            isAllowInsecureProtocol = true
            content { includeGroup("com.onyx.android.sdk") }
        }
    }
}

rootProject.name = "XiaonuoBoard"
include(":app")
