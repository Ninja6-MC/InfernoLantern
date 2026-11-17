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
val sqliteHostFixture = configurations.create("sqliteHostFixture")

dependencies {
    testImplementation("org.xerial:sqlite-jdbc:3.54.1.0") { isTransitive = false }
    sqliteRuntime("org.xerial:sqlite-jdbc:3.54.1.0") { isTransitive = false }
    sqliteHostFixture("org.xerial:sqlite-jdbc:3.49.1.0") { isTransitive = false }
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

// Preserve the unmodified driver/resources/notices behind a private loader. Never expose
// org.sqlite or a JDBC service to Paper's parent-first application classpath.
val privateJdbc = tasks.register<Copy>("privateJdbc") {
    from(sqliteRuntime)
    into(layout.buildDirectory.dir("private-jdbc/com/ninja6/infernolantern/internal"))
    rename { "sqlite-jdbc.jar" }
}
sourceSets.main { resources.srcDir(layout.buildDirectory.dir("private-jdbc")) }
tasks.processResources { dependsOn(privateJdbc) }
tasks.jar { duplicatesStrategy = DuplicatesStrategy.FAIL }

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
    doFirst {
        systemProperty("pluginJar", tasks.jar.get().archiveFile.get().asFile.absolutePath)
        systemProperty("hostSqliteJar", sqliteHostFixture.singleFile.absolutePath)
    }
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
