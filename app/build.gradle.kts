import com.google.gms.googleservices.GoogleServicesPlugin.MissingGoogleServicesStrategy
import java.net.URI
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.secrets)
  alias(libs.plugins.google.services)
  // Measures how much of the code the unit tests actually execute (§52 testing).
  id("jacoco")
}

/**
 * Baseline the CI run number is added to for `versionCode`.
 *
 * The number must only ever rise, because Android refuses to install an APK whose
 * versionCode is lower than the installed one (`INSTALL_FAILED_VERSION_DOWNGRADE`).
 * Adding a stable offset keeps every future CI build above the APKs published
 * before the offset existed, while `GITHUB_RUN_NUMBER` supplies the increment.
 */
val versionCodeOffset = 10_000

android {
  namespace = "com.aistudio.alphapulse"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.aistudio.alphapulse.kxmpzq"
    minSdk = 24
    targetSdk = 36

    // Release stamping: CI supplies BUILD_NUMBER (GitHub run_number, which only grows)
    // and GIT_SHA, so every produced APK is uniquely versioned and traceable to its
    // exact commit. versionCode must also rise monotonically for the package
    // installer to treat a new APK as an update, which the offset guarantees;
    // VERSION_CODE is an explicit override for a one-off rollback.
    val buildNumber = System.getenv("BUILD_NUMBER")?.toIntOrNull() ?: 0
    versionCode = System.getenv("VERSION_CODE")?.toIntOrNull()
      ?: (versionCodeOffset + buildNumber)
    val buildSha = System.getenv("GIT_SHA") ?: "dev"
    versionName = "1.0.$versionCode+$buildSha"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    // The Cactus Needle 3 engine (§30) is a per-ABI static library, so the app
    // builds a small JNI bridge with the NDK. The version is pinned so a build
    // cannot silently switch toolchains.
    ndkVersion = "27.0.12077973"

    externalNativeBuild {
      cmake {
        // c++_static keeps the C++ runtime inside our one shared library, so the
        // APK does not also have to ship libc++_shared.so.
        arguments += listOf("-DANDROID_STL=c++_static")
        // Exactly the ABI the APK split below ships. arm64-v8a is the only target:
        // every supported phone is 64-bit arm, Cactus publishes no 32-bit or x86
        // engine, and dropping the others halves the artifact size and the build
        // time without losing a supported device.
        abiFilters += listOf("arm64-v8a")
      }
    }
  }

  // Where the native bridge is defined. It links the vendored Cactus engine for
  // arm64/armv7 and reports an unsupported ABI otherwise.
  externalNativeBuild {
    cmake {
      path = file("src/main/cpp/CMakeLists.txt")
      version = "3.22.1"
    }
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
  // ONNX Runtime and the Cactus engine bundle native libraries per architecture.
  // Only arm64-v8a is shipped, so a single per-ABI APK is emitted and no universal
  // APK is built: one artifact, no wasted bandwidth, and a name the CI workflow can
  // upload on its own (`app-arm64-v8a-{debug,release}.apk`).
  splits {
    abi {
      isEnable = true
      reset()
      include("arm64-v8a")
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
  androidResources {
    // Needle 3 weights (.cact) and the TSFM graphs (.onnx) are already-compressed
    // model binaries. Deflating them only costs build time and blocks the engine
    // from mapping the checkpoint, so they are stored uncompressed in the APK.
    noCompress += listOf("cact", "onnx", "bin")
  }
  lint {
    checkReleaseBuilds = false
    abortOnError = false
  }

  // The vendored engine archives are already-compiled static libraries, so lint's
  // Java/Kotlin rules have nothing to say about them.
  packaging {
    jniLibs {
      useLegacyPackaging = false
    }
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

/**
 * Downloads the pinned Cactus Needle 3 checkpoint into the APK's assets.
 *
 * The artifact is static and published, so it is fetched at build time rather than
 * at runtime: the APK then ships the weights and no device has to download 35 MB
 * before the model can route anything. Size and SHA-256 are the same values
 * `ModelArtifacts.NEEDLE3` pins at runtime (`ModelArtifactSourceContractTest`
 * verifies them against the published file), so a build cannot package bytes the
 * app would later reject.
 *
 * Idempotent: a bundled file that already matches is left alone. Run it as
 * `./gradlew :app:bundleNeedle3Weights`; setting `BUNDLE_NEEDLE3=true` also hooks
 * it into `preBuild` for local release builds.
 */
/**
 * The pinned Needle 3 artifact, defined once for both tasks that use it.
 *
 * `bundleNeedle3Weights` puts these bytes into the APK; `verifyPackagedWeights`
 * checks they really are inside it. Two separate copies of the digest would let
 * the two disagree, which is the exact failure the pair exists to catch.
 */
val needle3AssetFile = layout.projectDirectory.file("src/main/assets/needle3.cact").asFile
val needle3Entry = "assets/needle3.cact"
val needle3ArtifactUrl = providers.gradleProperty("needle3Url")
  .orElse(providers.environmentVariable("NEEDLE3_URL"))
  .orElse("https://huggingface.co/Cactus-Compute/needle3/resolve/main/needle3.cact")
val needle3ArtifactSize = providers.gradleProperty("needle3SizeBytes")
  .orElse(providers.environmentVariable("NEEDLE3_SIZE_BYTES"))
  .orElse("35335380")
val needle3ArtifactSha = providers.gradleProperty("needle3Sha256")
  .orElse(providers.environmentVariable("NEEDLE3_SHA256"))
  .orElse("c9d915eca282ed42d1a09b143b592adb4cc6744ffe2d294adf5cfc5548170c38")

tasks.register("bundleNeedle3Weights") {
  group = "build"
  description = "Downloads and verifies needle3.cact into src/main/assets for packaging."

  val target = needle3AssetFile
  // Captured as locals so the task action never holds the script object itself,
  // which is what keeps it configuration-cache compatible.
  val pinnedUrl = needle3ArtifactUrl
  val pinnedSize = needle3ArtifactSize
  val pinnedSha = needle3ArtifactSha

  outputs.file(target)
  // The digest check is cheap once the file is present, so always re-verify.
  outputs.upToDateWhen { false }

  doLast {
    fun sha256(file: File): String {
      val digest = MessageDigest.getInstance("SHA-256")
      file.inputStream().use { input ->
        val buffer = ByteArray(1 shl 16)
        while (true) {
          val read = input.read(buffer)
          if (read <= 0) break
          digest.update(buffer, 0, read)
        }
      }
      return digest.digest().joinToString("") { "%02x".format(it) }
    }

    val expectedSize = pinnedSize.get().toLong()
    val expectedSha = pinnedSha.get()

    if (target.isFile && target.length() == expectedSize && sha256(target) == expectedSha) {
      logger.lifecycle(
        "Needle 3 weights already bundled: ${target.length()} bytes, sha-256 $expectedSha"
      )
      return@doLast
    }

    target.parentFile.mkdirs()
    val staging = File(target.parentFile, "needle3.cact.part")
    logger.lifecycle("Downloading Needle 3 weights from ${pinnedUrl.get()}")

    val connection = URI(pinnedUrl.get()).toURL().openConnection().apply {
      connectTimeout = 30_000
      readTimeout = 180_000
    }
    connection.getInputStream().use { input ->
      staging.outputStream().use { output -> input.copyTo(output) }
    }

    val actualSize = staging.length()
    val actualSha = sha256(staging)
    if (actualSize != expectedSize || actualSha != expectedSha) {
      staging.delete()
      throw GradleException(
        "Needle 3 artifact failed verification: $actualSize bytes, sha-256 $actualSha " +
          "(expected $expectedSize bytes, sha-256 $expectedSha)."
      )
    }

    if (target.exists() && !target.delete()) {
      staging.delete()
      throw GradleException("Could not replace the existing needle3.cact asset.")
    }
    if (!staging.renameTo(target)) {
      throw GradleException("Could not move the verified needle3.cact into src/main/assets.")
    }
    logger.lifecycle("Bundled Needle 3 weights: ${target.length()} bytes, sha-256 $actualSha")
  }
}

/**
 * Proves the assembled APK really carries the pinned checkpoint.
 *
 * The bundling task verifies the asset on disk, but packaging sits between that
 * file and the APK: a resource configuration change, a renamed asset or a stale
 * output could ship an APK without the weights while every earlier check still
 * passed. This opens each built APK as a ZIP, streams the entry and compares its
 * uncompressed size, compression method and SHA-256 with the same pinned values
 * `bundleNeedle3Weights` uses.
 *
 * Ordering only (no dependsOn): it verifies whatever the build produced and must
 * never assemble an APK silently, so a missing APK is a loud failure. The CI
 * build job runs it explicitly after the APK step.
 */
tasks.register("verifyPackagedWeights") {
  group = "verification"
  description = "Verifies the built APK carries the pinned needle3.cact asset."

  mustRunAfter("assembleDebug", "assembleRelease")

  val apkRoot = layout.buildDirectory.dir("outputs/apk").get().asFile
  // Resolved at configuration time: the task action then holds only values, which
  // is what keeps it configuration-cache compatible.
  val expectedSize = needle3ArtifactSize.get().toLong()
  val expectedSha = needle3ArtifactSha.get()
  val entryName = needle3Entry

  doLast {

    val apks = if (apkRoot.isDirectory) {
      apkRoot.walkTopDown().filter { it.isFile && it.extension == "apk" }.toList()
    } else {
      emptyList()
    }
    if (apks.isEmpty()) {
      throw GradleException(
        "No APK found under ${apkRoot.path}. Assemble one first " +
          "(./gradlew :app:assembleDebug) and run this task after it."
      )
    }

    for (apk in apks) {
      ZipFile(apk).use { zip ->
        val entry = zip.getEntry(entryName)
          ?: throw GradleException(
            "${apk.name} does not contain $entryName, so the device would have to " +
              "download the weights at runtime. Run ./gradlew :app:bundleNeedle3Weights " +
              "before assembling."
          )

        if (entry.method != ZipEntry.STORED) {
          throw GradleException(
            "$entryName in ${apk.name} is compressed (method ${entry.method}); " +
              "noCompress must cover .cact so the engine can map it in place."
          )
        }

        if (entry.size != expectedSize) {
          throw GradleException(
            "$entryName in ${apk.name} is ${entry.size} bytes, expected " +
              "$expectedSize, so the packaged weights are not the pinned artifact."
          )
        }

        val digest = MessageDigest.getInstance("SHA-256")
        zip.getInputStream(entry).use { input ->
          val buffer = ByteArray(1 shl 16)
          while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
          }
        }
        val actualSha = digest.digest().joinToString("") { "%02x".format(it) }
        if (actualSha != expectedSha) {
          throw GradleException(
            "$entryName in ${apk.name} has sha-256 $actualSha, expected " +
              "$expectedSha, so the APK does not carry the pinned weights."
          )
        }

        logger.lifecycle(
          "Verified ${apk.name}: $entryName, ${entry.size} bytes, sha-256 $actualSha"
        )
      }
    }
  }
}

if ((System.getenv("BUNDLE_NEEDLE3") ?: "false").equals("true", ignoreCase = true)) {
  tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn("bundleNeedle3Weights")
  }
}
