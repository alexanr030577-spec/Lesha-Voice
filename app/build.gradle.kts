plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "ru.lesha.voice"
    compileSdk = 35
    defaultConfig { applicationId = "ru.lesha.voice"; minSdk = 26; targetSdk = 35; versionCode = 2; versionName = "0.2" }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies { testImplementation("junit:junit:4.13.2") }
