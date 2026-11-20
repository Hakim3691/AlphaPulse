plugins {
  // AGP 9 compiles Kotlin in Android modules itself, so no separate Kotlin plugin
  // is applied here - adding one conflicts with the built-in `kotlin` extension.
  alias(libs.plugins.android.dynamic.feature)
}

/**
 * Phase 1 §5/D4: the on-demand `:proactive_trader` Play Feature Delivery module.
 *
 * It holds the auto-trader surface only. The base app reaches it through
 * :core_contract and by component name, never through a compile-time class
 * reference, so removing or not installing the module cannot change the base APK's
 * behaviour or layout.
 *
 * The module deliberately ships no native libraries: the base APK is arm64-only and
 * an ABI split on a feature module would double the artifact set for no code. Its
 * UI is built in code, so there is no resource merge to reconcile with the split.
 */
android {
  namespace = "com.alphapulse.proactive"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    minSdk = 24
    // The base APK is arm64-only, so the feature must resolve to the same ABI
    // output. Without this, AGP 9's resource linker cannot find the feature's
    // variant output when the base module uses ABI splits.
    ndk {
      abiFilters += listOf("arm64-v8a")
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
}

dependencies {
  // A dynamic feature must depend on its base module; the contract is shared.
  implementation(project(":app"))
  implementation(project(":core_contract"))
}
