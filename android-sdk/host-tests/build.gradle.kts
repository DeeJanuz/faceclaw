import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
 id("com.android.application")
 id("org.jetbrains.kotlin.android")
}
android {
 namespace = "com.faceclaw.sdk.hosttest"
 compileSdk = 35
 defaultConfig { applicationId = "com.faceclaw.sdk.hosttest"; minSdk = 27; targetSdk = 35; testInstrumentationRunner = "com.faceclaw.sdk.hosttest.BoundaryTest" }
 flavorDimensions += "host"
 productFlavors {
  create("standalone") { dimension = "host" }
  create("upstream") { dimension = "host"; applicationId = "com.faceclaw.app" }
  create("t3") { dimension = "host"; applicationId = "com.deejanuz.faceclaw.t3" }
 }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_11; targetCompatibility = JavaVersion.VERSION_11 }
 sourceSets["main"].java.srcDir(layout.buildDirectory.dir("generated/host"))
 sourceSets["main"].kotlin.srcDir(layout.buildDirectory.dir("generated/host-kotlin"))
}
kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_11) } }
dependencies { implementation(project(":sdk")) }

val copyHostSources by tasks.registering(Sync::class) {
 from(rootProject.file("../App_Resources/Android/src/main/java")) { include("com/faceclaw/app/FaceclawMessaging*.java", "com/faceclaw/app/FaceclawSms*.java", "com/faceclaw/app/FaceclawExternalApps.java", "com/faceclaw/app/FaceclawExtensions.java", "com/faceclaw/app/FaceclawExternalAppListener.java", "com/faceclaw/app/FaceclawAppSettingsActivity.java", "com/faceclaw/app/DisplayScheduler.java", "com/faceclaw/app/RenderCadence.java", "com/faceclaw/app/ExternalFrameOutcomeListener.java") }
 into(layout.buildDirectory.dir("generated/host"))
}
tasks.named("preBuild") { dependsOn(copyHostSources) }

val copyHostKotlinSources by tasks.registering(Sync::class) {
 from(rootProject.file("../native/kotlin/shared/src/commonMain/kotlin")) {
  include("com/faceclaw/app/callbacks/FaceclawSettingsListener.kt")
  include("com/faceclaw/app/util/ByteReader.kt")
 }
 from(rootProject.file("../native/kotlin/shared/src/androidMain/kotlin")) {
  include("com/faceclaw/app/AndroidByteReader.kt")
 }
 into(layout.buildDirectory.dir("generated/host-kotlin"))
}
tasks.named("preBuild") { dependsOn(copyHostKotlinSources) }
