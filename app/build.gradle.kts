plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "com.localcreator.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.localcreator.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "0.5.0"
    }
    signingConfigs {
        create("persistent") {
            System.getenv("LOCAL_CREATOR_KEYSTORE")?.takeIf { it.isNotBlank() }?.let {
                storeFile = file(it)
                storePassword = System.getenv("LOCAL_CREATOR_STORE_PASSWORD")
                keyAlias = System.getenv("LOCAL_CREATOR_KEY_ALIAS")
                keyPassword = System.getenv("LOCAL_CREATOR_KEY_PASSWORD")
            }
        }
    }
    buildTypes { getByName("release") { signingConfig = signingConfigs.getByName("persistent"); isMinifyEnabled = false } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.camera:camera-camera2:1.4.1")
    implementation("androidx.camera:camera-lifecycle:1.4.1")
    implementation("androidx.camera:camera-video:1.4.1")
    implementation("androidx.camera:camera-view:1.4.1")
}
