import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    `maven-publish`
}

android {
    namespace = "com.phiraphongbenzz.ktorch.noop"
    compileSdk = 36

    defaultConfig {
        minSdk = 23
    }

    buildFeatures {
        buildConfig = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

kotlin {
    explicitApi()
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // Only what the public API signatures need. No UI, no storage, no coroutines.
    api(libs.ktor.client.core)
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "com.github.phiraphongbenzz.ktorch"
            artifactId = "library-no-op"
            version = (findProperty("version") as String?)?.takeIf { it != "unspecified" } ?: "dev"
            afterEvaluate { from(components["release"]) }
        }
    }
}
