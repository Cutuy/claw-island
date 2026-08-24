plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.cutuy.clawisland"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.cutuy.clawisland"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"

        // Optional FCM identifiers. The app remains fully functional with SSE
        // when these are absent; no Firebase credentials belong in the repo.
        resValue("string", "firebase_application_id", System.getenv("CLAW_FIREBASE_APP_ID") ?: "")
        resValue("string", "firebase_api_key", System.getenv("CLAW_FIREBASE_API_KEY") ?: "")
        resValue("string", "firebase_project_id", System.getenv("CLAW_FIREBASE_PROJECT_ID") ?: "")
        resValue("string", "firebase_sender_id", System.getenv("CLAW_FIREBASE_SENDER_ID") ?: "")
        resValue("string", "claw_push_pairing_token", System.getenv("CLAW_PUSH_PAIRING_TOKEN") ?: "")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        viewBinding = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)
    implementation(libs.firebase.messaging)
    testImplementation(libs.junit)
}
