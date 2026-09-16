plugins { id("com.android.application") }
android {
 namespace = "com.faceclaw.textdensity"
 compileSdk = 35
 defaultConfig { applicationId = "com.faceclaw.textdensity"; minSdk = 27; targetSdk = 35; versionCode = 2; versionName = "1.1.0" }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_11; targetCompatibility = JavaVersion.VERSION_11 }
}
dependencies { implementation(project(":sdk")) }
