plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
}

// Mirrors a typical consumer app: AGP 9 (built-in Kotlin), compileSdk 37 (okhttp 5 under Ktor 3 needs
// it), targetSdk 36, minSdk 23, Java 17, XML + ViewBinding, no Compose, dev/uat/production.
android {
    namespace = "com.phiraphongbenzz.ktorch.sample"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.phiraphongbenzz.ktorch.sample"
        minSdk = 26 // the embedded test server needs 26; the libraries themselves are minSdk 23
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    flavorDimensions += "env"
    productFlavors {
        create("dev") { dimension = "env"; applicationIdSuffix = ".dev" }
        create("uat") { dimension = "env"; applicationIdSuffix = ".uat" }
        create("production") { dimension = "env" }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += setOf("META-INF/INDEX.LIST", "META-INF/io.netty.versions.properties")
    }
}

dependencies {
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.client.logging)
    implementation(libs.ktor.serialization.json)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.coroutines.android)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime)

    // Same wiring as Chucker: real inspector in debug, API-identical no-op in release.
    debugImplementation(project(":library"))
    debugImplementation(project(":library-room"))
    releaseImplementation(project(":library-no-op"))
}
