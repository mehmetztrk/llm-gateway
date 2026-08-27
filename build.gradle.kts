plugins {
    java
    // why test fixtures: the provider contract test must run against MockProvider in `test` and
    // against a live Ollama in `integrationTest`. Test fixtures are the Gradle-native way to share
    // test code between source sets — the alternative, bolting `sourceSets["test"].output` onto
    // the integration classpath, silently drags every unit test onto it too.
    `java-test-fixtures`
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
    alias(libs.plugins.spotless)
}

group = "io.github.mehmetztrk"
version = "0.1.0-SNAPSHOT"
description = "Multi-tenant, OpenAI-compatible LLM gateway"

java {
    // why a toolchain, not `sourceCompatibility`: Gradle will download and use a JDK 21
    // regardless of which JDK happens to be on PATH, so CI and laptop compile identically.
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

// Generates META-INF/build-info.properties, which actuator /info reads.
// why this, not @project.version@ token filtering in application.yml: Gradle's `expand()` uses
// ${...} as its own delimiter and would mangle every Spring property placeholder in the file.
springBoot {
    buildInfo()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-validation")

    // Generates META-INF/spring-configuration-metadata.json so IDEs autocomplete
    // our `gateway.*` properties and flag typos in application.yml.
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("io.projectreactor:reactor-test")
    testImplementation(libs.archunit.junit5)
    testImplementation(libs.wiremock)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    testFixturesImplementation("org.springframework.boot:spring-boot-starter-test")
    testFixturesImplementation("io.projectreactor:reactor-test")
}

// why a separate source set, not @Tag on the same one: integration tests need Docker and take
// seconds-to-minutes. Keeping them in their own set means `./gradlew test` stays a fast inner
// loop, while `check` still runs everything before a commit lands.
testing {
    suites {
        val test by getting(JvmTestSuite::class) {
            useJUnitJupiter()
        }

        register<JvmTestSuite>("integrationTest") {
            useJUnitJupiter()
            dependencies {
                implementation(project())
                implementation(testFixtures(project()))
                implementation("org.springframework.boot:spring-boot-starter-test")
                implementation("io.projectreactor:reactor-test")
            }
            targets {
                all {
                    testTask.configure {
                        shouldRunAfter(test)
                    }
                }
            }
        }
    }
}

configurations["integrationTestImplementation"].extendsFrom(configurations.implementation.get())
configurations["integrationTestRuntimeOnly"].extendsFrom(configurations.runtimeOnly.get())

tasks.named("check") {
    dependsOn(testing.suites.named("integrationTest"))
}

tasks.withType<Test>().configureEach {
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

spotless {
    java {
        target("src/**/*.java")
        palantirJavaFormat(libs.versions.palantirJavaFormat.get())
        removeUnusedImports()
        formatAnnotations()
        trimTrailingWhitespace()
        endWithNewline()
    }
    format("misc") {
        target("**/*.md", "**/*.yml", "**/*.yaml", "**/*.sh")
        targetExclude("**/build/**", "**/.gradle/**")
        trimTrailingWhitespace()
        endWithNewline()
    }
}
