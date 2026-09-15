plugins { id("com.android.application") }
android {
 namespace = "com.faceclaw.example"
 compileSdk = 35
 defaultConfig { applicationId = "com.faceclaw.sdkexample"; minSdk = 27; targetSdk = 35; versionCode = 1; versionName = "1.1.0-rc.1" }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_11; targetCompatibility = JavaVersion.VERSION_11 }
}
dependencies { implementation(project(":sdk")) }
