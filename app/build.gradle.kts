plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
}

android {
  namespace = "com.example"
  compileSdk = 35
  ndkVersion = "27.2.12479018"

  defaultConfig {
    applicationId = "com.aistudio.vulkancompat.qznvwt"
    minSdk = 29
    targetSdk = 35
    versionCode = 1
    versionName = "1.0.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

    ndk {
      abiFilters.add("arm64-v8a")
    }

    externalNativeBuild {
      cmake {
        arguments(
          "-DANDROID_STL=c++_shared",
          "-DANDROID_ARM_NEON=TRUE"
        )
        cppFlags("-std=c++17", "-O3", "-fvisibility=hidden", "-Wall")
      }
    }
  }

  externalNativeBuild {
    cmake {
      path = file("src/main/cpp/CMakeLists.txt")
      version = "3.22.1"
    }
  }

  // Signing: the release keystore is optional. When it is not available the
  // release build falls back to the debug keystore, so a fresh clone (or CI
  // without secrets) still produces an installable APK.
  signingConfigs {
    create("release") {
      val keystorePath = System.getenv("KEYSTORE_PATH") ?: "${rootDir}/my-upload-key.jks"
      val storeFileCandidate = file(keystorePath)
      val storePasswordEnv = System.getenv("STORE_PASSWORD")
      val keyPasswordEnv = System.getenv("KEY_PASSWORD")
      val keyAliasEnv = System.getenv("KEY_ALIAS") ?: "upload"

      if (storeFileCandidate.exists() && storePasswordEnv != null && keyPasswordEnv != null) {
        storeFile = storeFileCandidate
        storePassword = storePasswordEnv
        keyAlias = keyAliasEnv
        keyPassword = keyPasswordEnv
      }
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
      // Signed with the release keystore when one is configured, otherwise with
      // the debug keystore (so CI always produces an installable APK).
      signingConfig = if (signingConfigs.getByName("release").storeFile != null) {
        signingConfigs.getByName("release")
      } else {
        signingConfigs.getByName("debugConfig")
      }
    }
    debug {
      signingConfig = signingConfigs.getByName("debugConfig")
      ndk {
        debugSymbolLevel = "SYMBOL_TABLE"
      }
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
  dependenciesInfo {
    includeInApk = false
    includeInBundle = true
  }
  packaging {
    jniLibs {
      useLegacyPackaging = true
    }
  }
}

// Optional: a prebuilt arm64-v8a libdxvk.so dropped in third_party/dxvk-prebuilt
// (see the CI workflow) is packaged with the APK. The shell works without it:
// the DXVK build normally arrives inside the user's runtime package.
android.sourceSets.getByName("main").jniLibs.srcDir(
  rootProject.file("third_party/dxvk-prebuilt/lib/arm64-v8a")
)

dependencies {
  implementation(platform(libs.androidx.compose.bom))
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)

  testImplementation(libs.junit)
  testImplementation(libs.androidx.core)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  debugImplementation(libs.androidx.compose.ui.tooling)
}
