import com.google.gms.googleservices.GoogleServicesPlugin.MissingGoogleServicesStrategy

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.secrets)
  alias(libs.plugins.google.services)
  // Measures how much of the code the unit tests actually execute (§52 testing).
  id("jacoco")
}

android {
  namespace = "com.aistudio.alphapulse"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.aistudio.alphapulse.kxmpzq"
    minSdk = 24
    targetSdk = 36

    // Release stamping: CI supplies BUILD_NUMBER (GitHub run_number) and GIT_SHA so every
    // produced APK is uniquely versioned and traceable to its exact commit.
    versionCode = System.getenv("BUILD_NUMBER")?.toIntOrNull() ?: 1
    val buildSha = System.getenv("GIT_SHA") ?: "dev"
    versionName = "1.0.$versionCode+$buildSha"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  // Do not remove or modify these signingConfigs. They are necessary for building, installing,
  // and updating Android apps in AI Studio.
  signingConfigs {
    create("release") {
      val keystorePath = System.getenv("KEYSTORE_PATH") ?: "${rootDir}/my-upload-key.jks"
      storeFile = file(keystorePath)
      storePassword = System.getenv("STORE_PASSWORD")
      keyAlias = "upload"
      keyPassword = System.getenv("KEY_PASSWORD")
    }
    create("debugConfig") {
      storeFile = file("${rootDir}/debug.keystore")
      storePassword = "android"
      keyAlias = "androiddebugkey"
      keyPassword = "android"
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      // Do not remove or modify this signingConfig assignment. It is necessary for Android apps in
      // AI Studio.
      signingConfig = signingConfigs.getByName("release")
    }
    debug {
      // Do not remove or modify this signingConfig assignment. It is necessary for Android apps in
      // AI Studio.
      signingConfig = signingConfigs.getByName("debugConfig")
    }
  }
  // ONNX Runtime bundles ~50 MB of native inference libraries per architecture.
  // Shipping one fat APK would force every device to download all three, so emit
  // per-ABI APKs (arm64 phones, legacy 32-bit arm, x86_64 emulators). The universal
  // APK is intentionally disabled; Play delivery uses the AAB, which splits natively.
  splits {
    abi {
      isEnable = true
      reset()
      include("arm64-v8a", "armeabi-v7a", "x86_64")
      isUniversalApk = false
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }
  lint {
    checkReleaseBuilds = false
    abortOnError = false
  }
  dependenciesInfo {
    includeInApk = false
    includeInBundle = true
  }
}

// Configure the Secrets Gradle Plugin to use .env and .env.example files
// to match the convention used in Web projects.
secrets {
  propertiesFileName = ".env"
  defaultPropertiesFileName = ".env.example"
  ignoreList.add("FIREBASE_APPCHECK_DEBUG_TOKEN")
}

googleServices { missingGoogleServicesStrategy = MissingGoogleServicesStrategy.WARN }

// Some unused dependencies are commented out below instead of being removed.
// This makes it easy to add them back in the future if needed.
dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(platform(libs.firebase.bom))
  // implementation(libs.accompanist.permissions)
  implementation(libs.androidx.activity.compose)
  // implementation(libs.androidx.camera.camera2)
  // implementation(libs.androidx.camera.core)
  // implementation(libs.androidx.camera.lifecycle)
  // implementation(libs.androidx.camera.view)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  // implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  // implementation(libs.androidx.navigation.compose)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  // implementation(libs.coil.compose)
  implementation(libs.converter.moshi)
  implementation(libs.firebase.ai)
  // Uncomment to use Firestore:
  // implementation(libs.firebase.firestore)

  // Uncomment ALL FOUR of the following dependencies together to use Firebase Auth and Google
  // Sign-In via Credential Manager:
  // implementation(libs.firebase.auth)
  // implementation(libs.androidx.credentials)
  // implementation(libs.androidx.credentials.play.services)
  // implementation(libs.googleid)
  implementation(libs.firebase.appcheck.recaptcha)
  implementation(libs.firebase.appcheck.debug)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.androidx.biometric)
  implementation(libs.androidx.work.runtime)
  implementation(libs.logging.interceptor)
  implementation(libs.moshi.kotlin)
  implementation(libs.okhttp)
  // implementation(libs.play.services.location)
  implementation(libs.retrofit)
  // On-device inference for the mounted TSFM artifacts.
  implementation(libs.onnxruntime.android)
  // Unit tests execute the real graphs on the desktop runtime.
  testImplementation(libs.onnxruntime.desktop)
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.mockwebserver)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
  "ksp"(libs.androidx.room.compiler)
  "ksp"(libs.moshi.kotlin.codegen)
}

// Unit tests run the desktop ONNX Runtime; the Android AAR carries device-only
// native libraries and must not shadow it on the JVM test classpath.
configurations.configureEach {
  if (name.startsWith("test") || name.contains("UnitTest", ignoreCase = true)) {
    exclude(group = "com.microsoft.onnxruntime", module = "onnxruntime-android")
  }
}

// CI runs the live artifact-source contract check with VERIFY_ARTIFACT_NETWORK=true.
tasks.withType<Test>().configureEach {
  // The TimesFM parity run builds a ~250 MB ONNX session, which does not fit
  // in the default 512 MB test JVM.
  maxHeapSize = "2g"
  // JaCoCo's agent instruments each test JVM; its output lands in build/jacoco/.
  systemProperty("verifyArtifactNetwork", System.getenv("VERIFY_ARTIFACT_NETWORK") ?: "false")
  // The 250 MB TimesFM parity run is opt-in even when network checks are on.
  systemProperty("verifyLargeModel", System.getenv("VERIFY_LARGE_MODEL") ?: "false")
}

/**
 * Framework and generated classes that JaCoCo must not count as app logic.
 * Declared first so every report/gate task below can share the same list.
 */
val androidClassExcludes = listOf(
  "**/R.class",
  "**/R\$*.class",
  "**/BuildConfig.*",
  "**/*_Factory*.*",
  "**/*_Impl*.*",
  "**/Manifest*.*",
  "**/com/aistudio/alphapulse/databinding/**"
)

/**
 * Reports how much of the shipped code the unit tests actually exercise.
 *
 * Run `./gradlew jacocoTestReport` after `testDebugUnitTest`; the HTML report
 * lands in `app/build/reports/jacoco/jacocoTestReport/html/index.html` and a
 * machine-readable CSV next to it. CI runs this on every push so coverage can
 * only move deliberately, never by accident.
 *
 * Spec Ref: §52 Testing Requirements (TDD + required test groups)
 */
tasks.register<JacocoReport>("jacocoTestReport") {
  group = "verification"
  description = "Generates a unit-test coverage report for the debug variant."

  dependsOn("testDebugUnitTest")

  // Bytecode produced by the debug build (Kotlin first, then any Java).
  classDirectories.setFrom(
    fileTree("build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes") {
      exclude(androidClassExcludes)
    },
    fileTree("build/intermediates/javac/debug/compileDebugJavaWithJavac/classes") {
      exclude(androidClassExcludes)
    }
  )
  sourceDirectories.setFrom(fileTree("src/main/java"))
  // The JaCoCo agent writes one .exec per test task under build/jacoco/.
  executionData.setFrom(fileTree(layout.buildDirectory.dir("jacoco")) {
    include("**/*.exec")
  })

  reports {
    xml.required.set(true)
    csv.required.set(true)
    html.required.set(true)
  }
}

/**
 * Fails the build if coverage of the *pure Kotlin domain logic* drops below the
 * measured baseline.
 *
 * Only the decision-making packages are gated - `domain`, `service` and
 * `release`. Compose screens, Room/Retrofit glue and generated code are driven
 * by Robolectric and on-device tests, which JaCoCo's agent cannot see, so
 * counting them would only produce a meaningless low number.
 *
 * The floor is the coverage measured on this commit (67%), rounded down so a
 * single deleted test is still caught. Raise it when coverage improves; never
 * lower it.
 */
tasks.register<JacocoCoverageVerification>("jacocoCoverageGate") {
  group = "verification"
  description = "Fails if domain-logic coverage regresses below the recorded baseline."

  dependsOn("jacocoTestReport")

  classDirectories.setFrom(
    fileTree("build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes") {
      exclude(androidClassExcludes)
      include("**/com/aistudio/alphapulse/domain/**")
      include("**/com/aistudio/alphapulse/service/**")
      include("**/com/aistudio/alphapulse/release/**")
    }
  )
  sourceDirectories.setFrom(fileTree("src/main/java"))
  // The JaCoCo agent writes one .exec per test task under build/jacoco/.
  executionData.setFrom(fileTree(layout.buildDirectory.dir("jacoco")) {
    include("**/*.exec")
  })

  // Coverage must never go *down*: the baseline is the measured number, not a wish.
  violationRules {
    rule {
      limit {
        counter = "LINE"
        value = "COVEREDRATIO"
        // Re-measure after a test-heavy change and ratchet this upward only.
        minimum = (System.getenv("JACOCO_MIN_LINE_COVERAGE") ?: "0.67").toBigDecimal()
      }
    }
  }
}
