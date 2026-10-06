pluginManagement {
    includeBuild("build-logic")
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "Yuejian"
include(":core:ai", ":core:markdown", ":feature:conversation", ":app", ":core:model", ":core:database", ":core:files", ":core:pdf", ":core:designsystem", ":feature:library", ":feature:reader", ":feature:settings")
