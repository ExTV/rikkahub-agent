import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "me.rerere.locallm"
    compileSdk = 37
    defaultConfig {
        minSdk = 26
        // Single source of truth for the runtime's SDK version. LocalRuntimePreferences
        // compares this against the version its persisted accelerator / vision / crash
        // decisions were made under, and invalidates them when the dependency moves.
        // Generated from the version catalog so the two can never drift apart.
        buildConfigField(
            "String",
            "LITERTLM_SDK_VERSION",
            "\"${libs.versions.litertlm.get()}\"",
        )
    }
    buildFeatures {
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        // Return default values (null / 0 / false) for Android framework calls in JVM unit
        // tests instead of throwing "not mocked" exceptions. Required because production
        // code (e.g. LiteRtToolPrefix) calls android.util.Log which isn't available on JVM.
        unitTests.isReturnDefaultValues = true
    }
    sourceSets {
        getByName("main").jniLibs.setSrcDirs(listOf("src/main/jniLibs"))
    }
    packaging {
        jniLibs {
            // Avoid extracting native libs at install time so System.loadLibrary path stays cheap.
            useLegacyPackaging = false
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        optIn.add("kotlin.uuid.ExperimentalUuidApi")
        optIn.add("kotlin.time.ExperimentalTime")
    }
}

dependencies {
    api(project(":ai"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.okhttp)
    // LiteRT-LM runtime: loads .litertlm model files produced by the LiteRT-LM toolchain.
    //
    // We track the latest published release rather than mirroring Google AI Edge Gallery's
    // pin. The version is in the catalog; a bump automatically invalidates the persisted
    // accelerator / vision-unavailable / crash-recovery decisions (see
    // LocalRuntimePreferences.maybeInvalidateOnSdkUpgrade) so a new runtime always gets a
    // fresh probe instead of inheriting a workaround for a bug it may have fixed.
    //
    // A bump is API-safe but not automatically runtime-safe: 0.12.0 once native-SIGSEGV'd
    // inside liblitertlm_jni.so during vision-encoder init on Adreno-class devices. Always
    // load a multimodal model on a real device before shipping a bump.
    implementation(libs.litertlm.android)

    testImplementation(libs.junit)
}
