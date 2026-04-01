plugins {
    // To optionally create a shadow/fat jar that bundle up any non-core dependencies
    id("com.gradleup.shadow") version "8.3.5"
    // QuPath Gradle extension convention plugin
    id("qupath-conventions")
}

// Get version from Environment Variable (GitHub Actions) or fallback to VERSION file
// 1. Get the tag name from GitHub (e.g., "v1.0.3" or "v1.0.3-rc1")
val githubTag = System.getenv("GITHUB_REF_NAME")

// 2. Determine the final version string
val releaseVersion = if (githubTag != null && githubTag.startsWith("v")) {
    githubTag.removePrefix("v") // Use the tag (stripped of 'v')
} else {
    file("VERSION").readText().trim() // Fallback to your SNAPSHOT file
}

// TODO: Configure your extension here (please change the defaults!)
qupathExtension {
    name = "qupath-extension-bentofx"
    group = "io.github.qupath"
    version = releaseVersion
    description = "A simple QuPath extension"
    automaticModule = "io.github.qupath.extension.bentofx"
}

// TODO: Define your dependencies here
dependencies {

    // Extension relies on BentoFX
    implementation("software.coley:bento-fx:0.15.1")

    // Main dependencies for most QuPath extensions
    shadow(libs.bundles.qupath)
    shadow(libs.bundles.logging)
    shadow(libs.qupath.fxtras)

    // If you aren't using Groovy, this can be removed
    //shadow(libs.bundles.groovy)

    // For testing
    testImplementation(libs.bundles.qupath)
    testImplementation(libs.junit)

}

tasks.shadowJar {
    archiveClassifier.set("") 
    // No relocate needed - just package everything as-is
}