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

val sqliteRuntime = configurations.create("sqliteRuntime")

dependencies {
    implementation("org.xerial:sqlite-jdbc:3.54.1.0") { isTransitive = false }
    sqliteRuntime("org.xerial:sqlite-jdbc:3.54.1.0") { isTransitive = false }
    compileOnly("io.papermc.paper:paper-api:26.3.build.249-stable")
    testImplementation("io.papermc.paper:paper-api:26.3.build.249-stable")
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:6.1.3")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(25)
}

// Include only the pinned JDBC driver, its native resources, service entry and notices.
// Paper supplies the optional SLF4J API; do not bundle a second logging API or server API.
tasks.jar {
    from(sqliteRuntime.map { if (it.isDirectory) it else zipTree(it) }) {
        exclude("META-INF/MANIFEST.MF", "META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA")
    }
    duplicatesStrategy = DuplicatesStrategy.FAIL
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
    useJUnitPlatform()
    dependsOn(tasks.jar)
    doFirst { systemProperty("pluginJar", tasks.jar.get().archiveFile.get().asFile.absolutePath) }
    dependsOn(buildToolTests)
}

tasks.check {
    dependsOn(verifyPluginJar)
}

val prototypePacks = tasks.register<Exec>("prototypePacks") {
    group = "build"
    description = "Builds experimental Java/Bedrock packs and Geyser mappings."
    inputs.file("scripts/build_prototype_packs.py")
    inputs.dir("src/main/resources/assets/infernolantern")
    outputs.dir(layout.buildDirectory.dir("prototype-packs"))
    commandLine(python.get(), "scripts/build_prototype_packs.py")
}


tasks.register<JavaExec>("repositoryBenchmark") {
    group = "verification"
    description = "Runs a synthetic local SQLite workload; not a server load test."
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("com.ninja6.infernolantern.repository.RepositoryBenchmark")
}

// Local artifact preparation only: no deployment, server configuration or network access.
tasks.register<Exec>("itemTestKit") {
    group = "build"
    description = "Packages the checked disposable item harness, live worksheet and checksums."
    dependsOn(tasks.check)
    commandLine(python.get(), "scripts/build_item_test_kit.py", "--jar", tasks.jar.get().archiveFile.get().asFile.absolutePath)
}
