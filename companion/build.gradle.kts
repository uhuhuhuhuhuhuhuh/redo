import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.druvane.glasseshub"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.druvane.glasseshub"
        minSdk = 31
        targetSdk = 36
        versionCode = 2
        versionName = "0.1.1"

        manifestPlaceholders["mwdat_application_id"] = project.findProperty("mwdatApplicationId")?.toString() ?: "0"
        manifestPlaceholders["mwdat_client_token"] = project.findProperty("mwdatClientToken")?.toString() ?: "0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    implementation("androidx.activity:activity-ktx:1.13.0")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.meta.wearable:mwdat-core:0.8.0")
    implementation("com.meta.wearable:mwdat-camera:0.8.0")
    implementation("com.github.SourceUtils:jspeex:b7f6f864f0")
}
