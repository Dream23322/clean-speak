plugins {
    java
}

group = "dev.cleanspeak"
version = "1.0.0"

repositories {
    mavenCentral()
    // Official Paper repository first. Older snapshots such as 1.21.11 may have
    // been retired from the official snapshot index; the mirror is a fallback.
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.phoenix616.dev/")
    maven("https://repo.essentialsx.net/releases/")
}

dependencies {
    // Public API is a separate, dependency-free module. The plugin jar still
    // embeds the API classes for existing, single-jar deployments.
    compileOnly(project(":api"))
    testImplementation(project(":api"))

    // Paper 1.21.11 API. This is a compile-time dependency only; do not bundle it.
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")

    // Optional EssentialsX adapter. Only loaded when Essentials is installed.
    compileOnly("net.essentialsx:EssentialsX:2.21.2") {
        isTransitive = false
    }

    // Paper exposes Gson at runtime; used only for small JSON evidence/log files.
    compileOnly("com.google.code.gson:gson:2.11.0")

    testImplementation("com.google.code.gson:gson:2.11.0")
    testImplementation("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    testImplementation("org.yaml:snakeyaml:2.2")
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
    options.encoding = "UTF-8"
}

tasks.processResources {
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

tasks.jar {
    archiveBaseName.set("CleanSpeak")
    from("LICENSE")
    // Keep the API available at runtime without forcing a second jar on
    // operators. Third-party developers can compile against :api instead.
    from(project(":api").sourceSets.main.get().output)
}
