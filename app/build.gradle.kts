plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "ru.lesha.voice"
    compileSdk = 35
    defaultConfig { applicationId = "ru.lesha.voice"; minSdk = 26; targetSdk = 35; versionCode = 1; versionName = "0.1" }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
