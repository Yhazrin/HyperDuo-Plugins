pluginManagement { resolutionStrategy { eachPlugin { if (requested.id.id == "com.android.library") useModule("com.android.tools.build:gradle:8.13.0") } }; repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { google(); mavenCentral() } }
rootProject.name="HyperDuoPlugins"
include(":plugin-api",":plugin-gallery",":plugin-airpods",":plugin-standby")
