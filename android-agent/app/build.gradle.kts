plugins {
    id("com.android.application")
}

val marketingVersion: String = rootProject.file("../AppVersion.xcconfig")
    .readLines()
    .firstOrNull { it.trimStart().startsWith("MARKETING_VERSION") }
    ?.substringAfter("=")?.trim()
    ?: error("MARKETING_VERSION not found in AppVersion.xcconfig")

// Release signing comes from the environment (CI secrets, or release.sh reading the
// macOS Keychain). Without it, release builds are left unsigned.
val signingStore: String? = System.getenv("SIGNING_STORE_FILE")

android {
    namespace = "org.humanshaped.garageagent"
    compileSdk = 37

    defaultConfig {
        applicationId = "org.humanshaped.garageagent"
        minSdk = 33
        targetSdk = 36
        versionCode = 4
        versionName = marketingVersion
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            if (signingStore != null) {
                storeFile = file(signingStore)
                storeType = "pkcs12"
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS") ?: "garage-agent"
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD") ?: System.getenv("SIGNING_STORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (signingStore != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("junit:junit:4.13.2")
}
