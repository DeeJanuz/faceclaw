plugins { id("com.android.application") version "8.9.2" }
android {
 namespace = "org.example.widgets"
 compileSdk = 35
 defaultConfig { applicationId = "org.example.widgets"; minSdk = 27; targetSdk = 35; versionCode = 1; versionName = "1.0" }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_11; targetCompatibility = JavaVersion.VERSION_11 }
}
dependencies { implementation("com.faceclaw:sdk:${providers.gradleProperty("faceclawSdkVersion").get()}") }
