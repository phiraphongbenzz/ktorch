import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    `maven-publish`
}

android {
    namespace = "com.phiraphongbenzz.ktorch.room"
    compileSdk = 36

    defaultConfig {
        minSdk = 23
        consumerProguardFiles("consumer-rules.pro")
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
    // Same Room as the consuming app, so Gradle does not change its version.
    implementation(project(":library"))
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "com.github.phiraphongbenzz.ktorch"
            artifactId = "library-room"
            version = (findProperty("version") as String?)?.takeIf { it != "unspecified" } ?: "dev"
            afterEvaluate { from(components["release"]) }
        }
    }
}
