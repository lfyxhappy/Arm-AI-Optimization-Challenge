plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.jetbrains.kotlin.android)
}

val aichatBackendProfile = providers.gradleProperty("aichatBackend").orElse("cpu").get()
val validAichatBackendProfiles = setOf("cpu", "snapdragon-opencl", "snapdragon-htp")

require(aichatBackendProfile in validAichatBackendProfiles) {
    "Unsupported aichatBackend='$aichatBackendProfile'. Expected one of: " +
        validAichatBackendProfiles.sorted().joinToString(", ")
}

android {
    namespace = "com.arm.aichat"
    compileSdk = 36

    ndkVersion = "28.1.13356709"

    defaultConfig {
        minSdk = 33

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")

        ndk {
             abiFilters += listOf("arm64-v8a")
        }
        externalNativeBuild {
            cmake {
                arguments += "-DCMAKE_BUILD_TYPE=Release"
                arguments += "-DCMAKE_MESSAGE_LOG_LEVEL=DEBUG"
                arguments += "-DCMAKE_VERBOSE_MAKEFILE=ON"

                arguments += "-DBUILD_SHARED_LIBS=ON"
                arguments += "-DLLAMA_BUILD_APP=OFF"
                arguments += "-DLLAMA_BUILD_COMMON=ON"
                arguments += "-DLLAMA_OPENSSL=OFF"

                arguments += "-DGGML_NATIVE=OFF"
                arguments += "-DGGML_BACKEND_DL=ON"
                arguments += "-DGGML_CPU_ALL_VARIANTS=ON"
                arguments += "-DGGML_LLAMAFILE=OFF"
                // Select with -PaichatBackend=cpu|snapdragon-opencl|snapdragon-htp.
                // The CMake layer validates SDK prerequisites only for the selected
                // accelerator profile, so the portable CPU build remains unchanged.
                arguments += "-DAICHAT_BACKEND_PROFILE=$aichatBackendProfile"
                // The configured GitHub accelerator uses a local TLS proxy.
                // This is only a local dependency-fetch workaround; release
                // builds should use a trusted certificate chain or a vendored
                // KleidiAI source archive.
                arguments += "-DCMAKE_TLS_VERIFY=OFF"
            }
        }
        aarMetadata {
            minCompileSdk = 35
        }
    }
    externalNativeBuild {
        cmake {
            path("src/main/cpp/CMakeLists.txt")
            version = "4.3.3"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        jvmToolchain(17)

        compileOptions {
            targetCompatibility = JavaVersion.VERSION_17
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    publishing {
        singleVariant("release") {
            withJavadocJar()
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore.preferences)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
}
