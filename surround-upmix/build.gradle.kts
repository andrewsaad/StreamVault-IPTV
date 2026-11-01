// Stereo-to-5.1 upmix encoded as Dolby Digital (AC-3) so bonded surround speakers (e.g. a Sonos soundbar's
// rears over HDMI ARC) play stereo channels too. Native AC-3 encoder: native/build.sh -> src/main/jniLibs.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.streamvault.surround"
    compileSdk = 36

    defaultConfig {
        minSdk = 25
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        warningsAsErrors = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.media3.exoplayer)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
}
