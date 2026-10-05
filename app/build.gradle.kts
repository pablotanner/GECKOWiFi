plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.wire)
}

android {
    namespace = "com.thesis.geckowifi"
    compileSdk { version = release(37) }

    defaultConfig {
        applicationId = "com.thesis.geckowifi"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release { optimization { enable = false } }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Where the geopki server is reachable from, per test target - see LAB_SETUP.md.
    // SHOW_DEMO_NETWORKS: the hardcoded FakeDemoNetworks entries only make sense
    // on the emulator; on the lab testbed they'd sit next to the real APs.
    flavorDimensions += "target"
    productFlavors {
        create("emulator") {
            dimension = "target"
            buildConfigField("String", "GEOPKI_URL", "\"http://10.0.2.2:1234\"")
            buildConfigField("boolean", "SHOW_DEMO_NETWORKS", "true")
        }
        create("device") {
            dimension = "target"
            // Laptop's ICS address on the USB-C Ethernet segment, behind Router A/B.
            buildConfigField("String", "GEOPKI_URL", "\"http://192.168.137.1:1234\"")
            buildConfigField("boolean", "SHOW_DEMO_NETWORKS", "false")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.all {
            // mockk's bundled ByteBuddy doesn't yet recognize the class file
            // version emitted by newer JDKs (e.g. the JDK 25 toolchain Gradle
            // may select for the test worker) - this is ByteBuddy's own
            // documented escape hatch, not a workaround specific to us.
            it.systemProperty("net.bytebuddy.experimental", "true")
        }
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

wire {
    kotlin {
        javaInterop = false
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.material)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.wire.runtime)


    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    implementation("com.google.geometry:s2-geometry:2.0.0")
}

// s2-geometry brings full guava, which already contains ListenableFuture; the
// empty androidx "listenablefuture" stub then duplicates it and breaks APK assembly.
configurations.configureEach {
    exclude(group = "com.google.guava", module = "listenablefuture")
}