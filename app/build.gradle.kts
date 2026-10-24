plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.ouor.mobilegit"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.ouor.mobilegit"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    buildFeatures { buildConfig = true }
    packaging {
        resources {
            excludes += listOf(
                "META-INF/INDEX.LIST", "META-INF/DEPENDENCIES", "META-INF/*.kotlin_module",
                "META-INF/io.netty.versions.properties", "META-INF/LICENSE*", "META-INF/NOTICE*",
                "about.html", "plugin.properties", "OSGI-INF/**",
            )
        }
    }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation(libs.jgit)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)
    implementation(libs.serialization.json)
    implementation(libs.commonmark)
    implementation(libs.commonmark.gfm.tables)
    implementation(libs.commonmark.gfm.strikethrough)
    implementation(libs.commonmark.autolink)
    implementation(libs.work.runtime.ktx)
    implementation(libs.zxing.core)
    implementation(libs.slf4j.nop)
    implementation(libs.ngrok.java)
    // The android classifier jar carries both the JNI classes and libngrok_java.so (extracted at runtime by com.ngrok.Runtime).
    implementation(variantOf(libs.ngrok.java.native) { classifier("linux-android-aarch_64") }) { isTransitive = false }
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
