plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.magpie.usage"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.magpie.usage"
        minSdk = 24
        targetSdk = 35
        versionCode = 2
        versionName = "0.1.1"
    }

    signingConfigs {
        create("release") {
            // A release assemble must fail rather than emit an unsigned APK.
            // Debug/configuration/JVM-test tasks can run without secrets.
            val keystorePath = System.getenv("ANDROID_KEYSTORE_PATH")
                ?.takeIf { it.isNotBlank() }
            val keystorePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD")
                .orNull?.takeIf { it.isNotBlank() }
            val keyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS")
                .orNull?.takeIf { it.isNotBlank() }
            val keyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD")
                .orNull?.takeIf { it.isNotBlank() }

            if (listOf(keystorePath, keystorePassword, keyAlias, keyPassword).any { it == null }) {
                val packagesRelease = gradle.startParameter.taskNames.any {
                    it.contains("Release") || it == "assemble" || it.endsWith(":assemble")
                }
                val buildsRelease = gradle.startParameter.taskNames.any {
                    it == "build" || it.endsWith(":build")
                }
                if (packagesRelease || buildsRelease) {
                    throw GradleException(
                        "Release signing requires ANDROID_KEYSTORE_PATH, " +
                            "ANDROID_KEYSTORE_PASSWORD, ANDROID_KEY_ALIAS and ANDROID_KEY_PASSWORD.",
                    )
                }
                // Configuration-only and JVM test tasks do not need the
                // signing secret. release remains unsigned until CI injects it.
            }
            val keystoreFile = keystorePath?.let { file(it) }
            if (keystoreFile != null) {
                check(keystoreFile.isFile) {
                    "Release keystore does not exist at ANDROID_KEYSTORE_PATH."
                }
            }

            storeFile = keystoreFile
            storePassword = keystorePassword
            this.keyAlias = keyAlias
            this.keyPassword = keyPassword
            enableV1Signing = false
            enableV2Signing = true
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.core:core-ktx:1.13.1")
    testImplementation("junit:junit:4.13.2")
}
