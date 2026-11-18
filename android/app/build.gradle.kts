plugins {
    id("com.android.application")
}

android {
    namespace = "com.tactical.bodycam.agent"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.tactical.bodycam.agent"
        minSdk = 25
        targetSdk = 30
        versionCode = 1
        versionName = "1.0.0-prototype"
        multiDexEnabled = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    implementation("com.github.pedroSG94.RootEncoder:encoder:2.4.4")
    implementation("com.github.pedroSG94.RootEncoder:rtsp:2.4.4")
    implementation("com.github.pedroSG94.RootEncoder:library:2.4.4")

    implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")
    implementation("com.google.android.gms:play-services-location:21.0.1")

    implementation("io.github.webrtc-sdk:android:150.7871.01")
}
