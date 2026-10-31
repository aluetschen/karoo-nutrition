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
        // karoo-ext is hosted on GitHub Packages, which always needs a (free) GitHub token.
        maven {
            url = uri("https://maven.pkg.github.com/hammerheadnav/karoo-ext")
            credentials {
                username = providers.gradleProperty("gpr.user").orNull ?: System.getenv("USERNAME")
                password = providers.gradleProperty("gpr.key").orNull ?: System.getenv("TOKEN")
            }
        }
    }
}

rootProject.name = "karoo-fuel"
include(":core", ":app")
