rootProject.name = "llm-gateway"

// why a single module, not domain/application/adapter Gradle modules:
// multi-module would enforce the hexagonal boundaries at compile time, but costs build
// complexity on every change. ArchUnit (see ArchitectureTest) enforces the same rules in CI
// at a fraction of the friction, and the rules are readable as code rather than inferred
// from a directory layout. Revisit if this repo ever grows a second deployable.
