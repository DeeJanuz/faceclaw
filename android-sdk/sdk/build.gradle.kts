plugins { id("com.android.library"); id("maven-publish") }
// Coordinates default to com.faceclaw:sdk; jitpack.yml overrides them per tag.
group = providers.gradleProperty("sdkGroup").getOrElse("com.faceclaw")
version = providers.gradleProperty("sdkVersion").getOrElse("1.0.0")
android {
 namespace = "com.faceclaw.sdk"
 compileSdk = 35
 defaultConfig { minSdk = 27; consumerProguardFiles("consumer-rules.pro") }
 buildFeatures { aidl = true }
 sourceSets["test"].resources.srcDir(rootProject.file("test-vectors"))
 compileOptions { sourceCompatibility = JavaVersion.VERSION_11; targetCompatibility = JavaVersion.VERSION_11 }
 testOptions { unitTests.isReturnDefaultValues = true }
 publishing { singleVariant("release") }
}
afterEvaluate {
 publishing {
  publications {
   create<MavenPublication>("release") {
    from(components["release"])
    artifactId = "sdk"
    pom { name.set("Faceclaw Android SDK"); url.set("https://github.com/DeeJanuz/faceclaw") }
   }
  }
 }
}
dependencies {
 testImplementation("junit:junit:4.13.2")
 testImplementation("org.json:json:20240303")
}
