pluginManagement {
  repositories {
    google {
      content {
        includeGroupByRegex("com\\.android.*")
        includeGroupByRegex("com\\.google.*")
        includeGroupByRegex("androidx.*")
      }
    }
    mavenCentral()
    gradlePluginPortal()
  }
}

plugins { id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0" }

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    google()
    mavenCentral()
  }
}

rootProject.name = "AlphaPulse"

include(":app")
// Phase 1 D4: the auto-trader is an on-demand dynamic feature. Its public contract
// lives in :core_contract so :app can drive it without a compile-time dependency
// on the feature module itself.
include(":core_contract")
// Phase 1 §5: the auto-trader ships as an on-demand Play Feature Delivery module.
include(":proactive_trader")
