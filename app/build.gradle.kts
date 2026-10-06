plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.barangay_superapp"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.barangay_superapp"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.appcompat)
    implementation(libs.cardview)
    implementation(libs.material)
    
    // Server / Backend Integration Libraries
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.10.1")

    // OpenStreetMap native map view for pinning incident locations
    implementation("org.osmdroid:osmdroid-android:6.1.20")

    // Background check for request / report updates -> phone notifications (StatusCheckWorker)
    implementation("androidx.work:work-runtime:2.10.0")

    testImplementation(libs.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.ext.junit)
}