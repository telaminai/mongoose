# Project Guidelines

## Project Overview
Fluxtion Server is a Java-based event-driven server framework built around Fluxtion's composable services and agents. It provides infrastructure for configuring, composing, and running event processors and services with support for scheduling, batching, dispatching, and server control.

Core components visible in this repository include:
- Configuration (com.telamin.mongoose.config) to bootstrap and wire services.
- Service extensions (com.telamin.mongoose.service.extension) for event sources and agent-hosted services.
- Dispatch, duty-cycle, scheduler, and admin services under com.telamin.mongoose.service.*.
- Internal runners and injectors (com.telamin.mongoose.internal) for composing and running agents/processors.
- Example, integration, benchmark, and stress tests under src/test/java/com/telamin/mongoose.

## Repository Structure
- src/main/java: Production code under package com.telamin.mongoose and subpackages.
- src/test/java: Unit, integration, and performance-related tests.
- docs/: Architecture notes, standards, and sequence diagrams.
- pom.xml: Maven build configuration.

## Build & Configuration (Project-specific)
- Java toolchain: Java 21 (maven.compiler.source/target=21). Ensure JDK 21+ is available. Maven enforcer allows 17+, but the compiler is set to 21.
- Core dependencies: com.fluxtion:runtime ${fluxtion.version} (currently 9.7.14).
- Test stack: JUnit Jupiter (JUnit 5), JMH annotations available in test scope, optional OpenHFT Affinity for pinning (tests only).
- Lombok: Provided scope, annotation processing enabled via maven-compiler-plugin.
- Important Maven plugins:
  - maven-compiler-plugin 3.14.0 with annotationProcessorPaths for lombok and JMH (testCompile execution).
  - maven-enforcer-plugin: dependency convergence is checked; Java >=17 enforced (non-fatal).
  - release profile includes source/javadoc jars, GPG signing, central publishing helpers.

Build commands:
- Fast package without tests: mvn -q -DskipTests package
- Full package with tests: mvn -q package
- Install to local repo: mvn -q install

Notes:
- If using Lombok in IDEs, enable annotation processing.
- No special system properties are required for unit tests by default. Some integration/perf tests may rely on timing and CPU affinity; prefer running the standard unit tests for quick iterations.

## Testing
Framework & conventions:
- JUnit 5 (org.junit.jupiter). Tests live under src/test/java.
- Prefer plain unit tests for services/processors. Heavier benchmarks under com.telamin.mongoose.benchmark and stress packages.

Running tests:
- All tests: mvn -q test
- One class by FQN: mvn -q -Dtest=com.telamin.mongoose.test.SmokeDocTest test
- Pattern (Surefire glob): mvn -q -Dtest="*EventSource*Test" test
- One method (JUnit 5): mvn -q -Dtest=com.telamin.mongoose.service.AbstractEventSourceServiceTest#shouldStartStopLifecycle test

Adding a new test:
- Place under an appropriate package reflecting the production code being tested.
- Use JUnit 5 annotations (@Test, @BeforeEach/@AfterEach, @ParameterizedTest if needed).
- Avoid reliance on wall-clock sleeps; leverage existing helpers in src/test/java/com/telamin/mongoose/test and util packages when possible.

Demonstration (verified):
- A minimal smoke test can be added under src/test/java/com/telamin/mongoose/test (see repository tests) and executed successfully with the command:
  - mvn -q -Dtest=com.telamin.mongoose.test.SmokeDocTest test
  This validates the documented flow for adding and running a new test.

## Additional Development Information
- Code style: keep classes cohesive, factor internal utilities under com.telamin.mongoose.internal; prefer small single‑responsibility methods. Add Javadoc where intent is non-obvious.
- Configuration wiring: review AppConfig and ServerConfigurator when changing service lifecycles or bindings to avoid runtime mis-wiring.
- Performance tests: JMH deps are available in test scope; annotate benchmarks in test sources if needed. Do not include benchmarks in main sources.
- Affinity/Pinning: net.openhft:affinity is available (test scope) for deterministic perf tests; keep optional and guarded by assumptions so normal CI can run without special privileges.
- Java modules are not used; standard classpath project.

## Contribution Tips
- Prefer minimal, targeted changes per issue; include or update tests alongside behavior changes.
- Search for existing helpers in com.telamin.mongoose.internal and service packages before introducing new utilities.
- When editing configuration, check AppConfig and ServerConfigurator for injection/wiring impacts.
