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
    implementation("org.springframework.boot:spring-boot-starter-security")

    // why plain JDBC and not R2DBC in a reactive app: see ADR-0006. Short version — Flyway is
    // blocking anyway, and the DB is kept off the request hot path by a Caffeine cache, so the
    // simpler and better-supported stack wins.
    // Reactive Redis (Lettuce). Unlike JDBC this is genuinely non-blocking, so the rate limiter
    // sits directly on the request path with no scheduler hop — see ADR-0006 for the contrast.
    implementation("org.springframework.boot:spring-boot-starter-data-redis-reactive")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("com.github.ben-manes.caffeine:caffeine")
    runtimeOnly("org.postgresql:postgresql")

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

        // why BlockHound gets its own source set and JVM: BlockHound.install() instruments the
        // whole JVM permanently, and a Spring context start-up trips it on legitimately blocking
        // framework code. Installed inside the shared `test` JVM it would either fail unrelated
        // tests or need such a broad allow-list that it stopped detecting anything. Its own task
        // means the instrumentation is scoped to the code it is meant to police.
        register<JvmTestSuite>("blockHoundTest") {
            useJUnitJupiter()
            dependencies {
                implementation(project())
                implementation(testFixtures(project()))
                implementation("org.springframework.boot:spring-boot-starter-test")
                implementation("io.projectreactor:reactor-test")
                implementation(libs.blockhound)
            }
            targets {
                all {
                    testTask.configure {
                        // BlockHound rewrites JDK methods; without this the JVM refuses the
                        // retransformation on Java 13+.
                        jvmArgs("-XX:+AllowRedefinitionToAddDeleteMethods")
                    }
                }
            }
        }

        register<JvmTestSuite>("integrationTest") {
            useJUnitJupiter()
            dependencies {
                implementation(project())
                implementation(testFixtures(project()))
                implementation("org.springframework.boot:spring-boot-starter-test")
                implementation("org.springframework.boot:spring-boot-testcontainers")
                implementation("org.springframework.security:spring-security-test")
                implementation("io.projectreactor:reactor-test")
                implementation("org.testcontainers:junit-jupiter")
                implementation("org.testcontainers:postgresql")
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
    dependsOn(testing.suites.named("integrationTest"), testing.suites.named("blockHoundTest"))
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
