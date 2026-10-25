import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
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
        versionCode = 3
        versionName = "0.2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Release signing per signing/SIGNING.md; release.p12 and keystore.properties are gitignored.
    val keystoreProps = rootProject.file("signing/keystore.properties").takeIf { it.exists() }
        ?.let { f -> Properties().apply { f.inputStream().use { load(it) } } }
    signingConfigs {
        keystoreProps?.let { p ->
            create("release") {
                storeFile = rootProject.file("signing/release.p12")
                storeType = "PKCS12"
                storePassword = p.getProperty("storePassword")
                keyAlias = "release"
                keyPassword = p.getProperty("storePassword")
            }
        }
    }
    // One APK per ABI (app-arm64-v8a-*.apk, app-x86_64-*.apk).
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = false
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
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
    buildFeatures { buildConfig = true; compose = true }
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
    implementation(libs.ktor.server.compression)
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)
    implementation(libs.serialization.json)
    implementation(libs.commonmark)
    implementation(libs.commonmark.gfm.tables)
    implementation(libs.commonmark.gfm.strikethrough)
    implementation(libs.commonmark.autolink)
    implementation(libs.jsoup)
    implementation(libs.work.runtime.ktx)
    implementation(libs.zxing.core)
    implementation(libs.slf4j.nop)
    // UI (docs/ui/design-system.md): Compose + Material 3 + Navigation
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons.extended)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    implementation(libs.activity.compose)
    implementation(libs.navigation.compose)
    implementation(libs.lifecycle.runtime.compose)
    // F-04: ngrok-go v2 (MIT) bound with gomobile; rebuild with ngrok-mobile/build.sh (arm64 + x86_64, 16KB pages).
    implementation(files("libs/ngrokmobile.aar"))
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
