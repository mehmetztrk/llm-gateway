package io.github.mehmetztrk.llmgateway.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS;
import static com.tngtech.archunit.library.GeneralCodingRules.NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Executable version of the layering rules in CLAUDE.md.
 *
 * <p>why ArchUnit instead of separate Gradle modules: multi-module builds enforce the same
 * boundaries via the compiler, but every new adapter then costs a module, a build file and a
 * dependency edge. These rules cost one test class, run in CI, and — unlike a directory
 * convention — actually fail the build when someone violates them.
 *
 * <p>{@code allowEmptyShould(true)} is set because packages fill in milestone by milestone; without
 * it ArchUnit fails a rule that currently matches no classes, which would mean deleting and
 * re-adding rules as the project grows.
 */
@AnalyzeClasses(packages = "io.github.mehmetztrk.llmgateway", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule dependencies_point_inwards = layeredArchitecture()
            .consideringOnlyDependenciesInLayers()
            .layer("Domain")
            .definedBy("..llmgateway.domain..")
            .layer("Application")
            .definedBy("..llmgateway.application..")
            .layer("Adapter")
            .definedBy("..llmgateway.adapter..")
            .layer("Config")
            .definedBy("..llmgateway.config..")
            .whereLayer("Adapter")
            .mayOnlyBeAccessedByLayers("Config")
            .whereLayer("Application")
            .mayOnlyBeAccessedByLayers("Adapter", "Config")
            .whereLayer("Domain")
            .mayOnlyBeAccessedByLayers("Application", "Adapter", "Config")
            .as("dependencies must point inwards: adapter -> application -> domain")
            .allowEmptyShould(true);

    /**
     * The domain is plain Java. If it ever imports Spring or Reactor, the business rules have
     * become inseparable from the delivery mechanism and can no longer be unit-tested without a
     * container.
     */
    @ArchTest
    static final ArchRule domain_is_framework_free = noClasses()
            .that()
            .resideInAPackage("..llmgateway.domain..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "org.springframework..",
                    "reactor..",
                    "jakarta..",
                    "com.fasterxml.jackson..",
                    "io.github.resilience4j..",
                    "io.opentelemetry..")
            .as("domain must not depend on any framework")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule application_does_not_know_about_adapters = noClasses()
            .that()
            .resideInAPackage("..llmgateway.application..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..llmgateway.adapter..")
            .as("application must depend on ports, never on adapter implementations")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule no_field_injection = noFields()
            .should()
            .beAnnotatedWith("org.springframework.beans.factory.annotation.Autowired")
            .as("constructor injection only: no field @Autowired")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule no_java_util_logging = NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING.allowEmptyShould(true);

    @ArchTest
    static final ArchRule no_standard_streams = NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS.allowEmptyShould(true);
}
