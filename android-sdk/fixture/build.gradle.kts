plugins { id("com.android.application") }
android {
 namespace = "com.faceclaw.sdk.fixture"
 compileSdk = 35
 defaultConfig { applicationId = "com.faceclaw.sdk.fixture"; minSdk = 27; targetSdk = 35; testInstrumentationRunner = "android.test.InstrumentationTestRunner" }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_11; targetCompatibility = JavaVersion.VERSION_11 }
 sourceSets["main"].java.srcDir("../examples")

}
dependencies { implementation(project(":sdk")) }
