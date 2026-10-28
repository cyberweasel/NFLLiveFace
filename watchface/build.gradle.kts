plugins {
    id("com.android.application")
}

android {
    namespace = "com.example.nflwatchface.watchface"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.nflwatchface.watchface"
        minSdk = 30
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }
}
// No dependencies: a Watch Face Format face has no code.
