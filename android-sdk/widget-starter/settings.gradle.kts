pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
 repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
 repositories {
  google(); mavenCentral()
  providers.gradleProperty("faceclawSdkRepository").orNull?.let { maven { url = uri(it) } }
 }
}
rootProject.name = "portable-faceclaw-widget"
