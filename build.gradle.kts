plugins {
    // Support writing the extension in Groovy (remove this if you don't want to)
    //groovy
    // To optionally create a shadow/fat jar that bundle up any non-core dependencies
    id("com.gradleup.shadow") version "8.3.5"
    // QuPath Gradle extension convention plugin
    id("qupath-conventions")
}

// TODO: Configure your extension here (please change the defaults!)
qupathExtension {
    name = "qupath-extension-bentofx"
    group = "io.github.qupath"
    version = "0.1.0-SNAPSHOT"
    description = "A simple QuPath extension"
    automaticModule = "io.github.qupath.extension.bentofx"
}

// TODO: Define your dependencies here
dependencies {

    // Extension relies on BentoFX
    implementation("software.coley:bento-fx:0.10.0")

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