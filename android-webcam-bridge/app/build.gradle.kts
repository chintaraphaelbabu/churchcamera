plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jlleitschuh.gradle.ktlint")
}

val releaseSigningNames =
    listOf("RELEASE_STORE_FILE", "RELEASE_STORE_PASSWORD", "RELEASE_KEY_ALIAS", "RELEASE_KEY_PASSWORD")
val releaseSigningValues =
    releaseSigningNames.associateWith { name ->
        providers.gradleProperty(name).orElse(providers.environmentVariable(name)).orNull
    }
val missingReleaseSigningValues = releaseSigningNames.filter { releaseSigningValues[it].isNullOrBlank() }

android {
    namespace = "com.raphael.androidwebcambridge"
    compileSdk = 35

    if (missingReleaseSigningValues.isEmpty()) {
        signingConfigs {
            create("release") {
                storeFile = file(releaseSigningValues.getValue("RELEASE_STORE_FILE")!!)
                storePassword = releaseSigningValues.getValue("RELEASE_STORE_PASSWORD")
                keyAlias = releaseSigningValues.getValue("RELEASE_KEY_ALIAS")
                keyPassword = releaseSigningValues.getValue("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            if (missingReleaseSigningValues.isEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    defaultConfig {
        applicationId = "com.raphael.androidwebcambridge"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "1.3.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
    }

    lint {
        disable += setOf("UnsafeOptInUsageError", "MissingPermission", "OldTargetApi", "GradleDependency", "NewerVersionAvailable")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

gradle.taskGraph.whenReady {
    if (allTasks.any { it.name.contains("release", ignoreCase = true) }) {
        check(missingReleaseSigningValues.isEmpty()) {
            "Release signing credentials are missing. Configure these Gradle properties or environment variables: " +
                missingReleaseSigningValues.joinToString(", ")
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")

    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.foundation:foundation")

    implementation("androidx.camera:camera-camera2:1.5.0")
    implementation("androidx.camera:camera-lifecycle:1.5.0")
    implementation("androidx.camera:camera-view:1.5.0")
    implementation("com.google.mlkit:face-detection:16.1.7")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation("junit:junit:4.13.2")
}
