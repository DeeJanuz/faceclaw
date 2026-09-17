pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { google(); mavenCentral() } }
rootProject.name = "faceclaw-android-sdk"
include(":sdk")
include(":fixture", ":host-tests")
include(":priority-demo")

include(":standalone-example")
include(":text-density-demo")
include(":diagnostics-demo")
