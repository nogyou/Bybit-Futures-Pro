plugins { id("com.android.application") }
android {
    namespace = "com.btc.analyzer"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.btc.analyzer"
        minSdk = 26
        targetSdk = 35
        versionCode = 4
        versionName = "2.1.0-beta"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies { implementation("com.squareup.okhttp3:okhttp:4.12.0") }
