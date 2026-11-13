plugins {
    java
}

group = "com.ninja6.infernolantern"

val python = providers.environmentVariable("PYTHON").orElse("python3")
val releaseTag = providers.gradleProperty("releaseTag")
version = providers.exec {
    commandLine(buildList {
        add(python.get())
        add("scripts/build_version.py")
        if (releaseTag.isPresent) {
            add("--release-tag")
            add(releaseTag.get())
        }
    })
}.standardOutput.asText.get().trim()

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.3.build.249-stable")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
}

val pluginVersion = version.toString()

tasks.processResources {
    inputs.property("version", pluginVersion)
    filesMatching("plugin.yml") {
        expand("version" to pluginVersion)
    }
}

tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

val buildToolTests = tasks.register<Exec>("buildToolTests") {
    group = "verification"
    description = "Tests source version selection and packaged artifact rejection."
    commandLine(python.get(), "-m", "unittest", "discover", "-s", "scripts/tests", "-v")
}

val verifyPluginJar = tasks.register<Exec>("verifyPluginJar") {
    group = "verification"
    description = "Checks the exact deployable JAR set and embedded descriptor."
    dependsOn(tasks.jar)
    commandLine(python.get(), "scripts/verify_plugin_jar.py", "build/libs", pluginVersion)
}

tasks.test {
    dependsOn(buildToolTests)
}

tasks.check {
    dependsOn(verifyPluginJar)
}
