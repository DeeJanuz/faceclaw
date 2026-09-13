plugins { id("com.android.library") }
group = "com.faceclaw"
version = "1.0.0"
android {
 namespace = "com.faceclaw.sdk"
 compileSdk = 35
 defaultConfig { minSdk = 27; consumerProguardFiles("consumer-rules.pro") }
 buildFeatures { aidl = true }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_11; targetCompatibility = JavaVersion.VERSION_11 }
 testOptions { unitTests.isReturnDefaultValues = true }
}
dependencies {
 testImplementation("junit:junit:4.13.2")
 testImplementation("org.json:json:20240303")
}
