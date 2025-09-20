# Mongoose Server Coding Standards and Best Practices

## Introduction

This document outlines the coding standards and best practices for the Mongoose server project. Following these standards ensures consistency across the codebase, improves code quality, and makes the codebase more maintainable.

## Table of Contents

1. [Code Style and Formatting](#code-style-and-formatting)
2. [Naming Conventions](#naming-conventions)
3. [Documentation Standards](#documentation-standards)
4. [Error Handling](#error-handling)
5. [Logging](#logging)
6. [Testing](#testing)
7. [Performance Considerations](#performance-considerations)
8. [Security Best Practices](#security-best-practices)
9. [Code Organization](#code-organization)
10. [Dependency Management](#dependency-management)

## Code Style and Formatting

### File Structure

Each Java file should follow this structure:

1. License header
2. Package declaration
3. Import statements (organized and grouped)
4. Class/interface declaration with Javadoc
5. Constants
6. Fields
7. Constructors
8. Methods
9. Inner classes/interfaces

Example:

```java
/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package com.telamin.mongoose.example;

import java.util.List;
import java.util.Map;

import com.telamin.fluxtion.runtime.annotations.OnEvent;
import com.telamin.mongoose.config.ConfigMap;

/**
 * Example class demonstrating the file structure.
 */
public class ExampleClass {

    public static final String CONSTANT_VALUE = "value";
    
    private final String field;
    private Map<String, Object> configMap;
    
    public ExampleClass(String field) {
        this.field = field;
    }
    
    public void processEvent(Object event) {
        // Method implementation
    }
    
    private class InnerClass {
        // Inner class implementation
    }
}
```

### Indentation and Line Wrapping

- Use 4 spaces for indentation, not tabs
- Maximum line length should be 120 characters
- When wrapping lines, indent continuation lines by 8 spaces
- Method chaining should align dots on new lines

Example:

```java
// Good - proper indentation and line wrapping
public void methodWithLongSignature(String parameter1, String parameter2,
        int parameter3, Map<String, Object> parameter4) {
    // Method implementation
}

// Good - method chaining with aligned dots
eventProcessor.setAuditLogProcessor(logRecordListener)
              .setAuditLogLevel(logLevel)
              .init();
```

### Braces

- Opening braces should be on the same line as the declaration
- Closing braces should be on their own line
- Always use braces for control statements, even for single-line blocks

Example:

```java
// Good - braces on same line as declaration
if (condition) {
    doSomething();
} else {
    doSomethingElse();
}

// Bad - missing braces for single-line block
if (condition)
    doSomething();
```

### Whitespace

- Use a space after control keywords (if, for, while, etc.)
- Use spaces around operators
- No space between method name and opening parenthesis
- No space after opening parenthesis or before closing parenthesis

Example:

```java
// Good - proper whitespace
for (int i = 0; i < 10; i++) {
    doSomething(i);
}

// Bad - improper whitespace
for(int i=0;i<10;i++){
    doSomething (i);
}
```

## Naming Conventions

### Packages

- Package names should be all lowercase
- Use reverse domain name notation (com.telamin.mongoose)
- Use meaningful and descriptive package names

Example:
```
com.telamin.mongoose.dispatch
com.telamin.mongoose.config
com.telamin.mongoose.service.admin
```

### Classes and Interfaces

- Class and interface names should be nouns in UpperCamelCase
- Interface names should not start with 'I'
- Abstract classes may start with 'Abstract'

Example:
```java
public class EventProcessor { }
public interface EventSource { }
public abstract class AbstractEventSourceService { }
```

### Methods

- Method names should be verbs in lowerCamelCase
- Methods that return boolean should start with 'is', 'has', or similar

Example:
```java
public void processEvent(Object event) { }
public boolean isProcessorRegistered(String name) { }
```

### Variables and Fields

- Variable and field names should be in lowerCamelCase
- Constants should be in UPPER_SNAKE_CASE
- Field names should be descriptive and avoid abbreviations

Example:
```java
private final EventFlowManager flowManager;
private static final String CONFIG_FILE_PROPERTY = "fluxtionserver.config.file";
```

### Generics

- Type parameters should be single uppercase letters or descriptive names in UpperCamelCase
- Common type parameters:
  - T for a general type
  - E for element type
  - K for key type
  - V for value type

Example:
```java
public class EventProcessorConfig<T extends EventProcessor<?>> { }
public interface EventSource<T> { }
```

## Documentation Standards

### File Headers

All source files should include the SPDX license header:

```java
/*
 * SPDX-FileCopyrightText: © 2025 Gregory Higgins <greg.higgins@v12technology.com>
 * SPDX-License-Identifier: AGPL-3.0-only
 */
```

### Javadoc

- All public classes, interfaces, and methods should have Javadoc
- Javadoc should describe what the class/method does, not how it does it
- Use `@param`, `@return`, `@throws` tags as appropriate
- Link to related classes using `{@link ClassName}`

Example:

```java
/**
 * Publishes events to {@link EventToQueuePublisher}. Register an {@link EventSource} instance with {@link EventFlowManager}
 * to receive the target queue via the setEventToQueuePublisher callback method.
 *
 * @param <T> the type of events published by this source
 */
public interface EventSource<T> {
    /**
     * Subscribes to events from this source.
     *
     * @param eventSourceKey the subscription key
     */
    void subscribe(EventSubscriptionKey<T> eventSourceKey);
}
```

### Comments

- Use comments to explain why, not what
- Use TODO comments for code that needs to be completed
- Avoid commented-out code

Example:

```java
// Good - explains why
// Use a concurrent map to handle multiple threads accessing the services
private final ConcurrentHashMap<String, Service<?>> registeredServices = new ConcurrentHashMap<>();

// Bad - explains what, which is already clear from the code
// Create a new map
Map<String, Object> map = new HashMap<>();
```

## Error Handling

### Exceptions

- Use checked exceptions for recoverable errors
- Use unchecked exceptions for programming errors
- Create custom exceptions for specific error cases
- Always include meaningful error messages

Example:

```java
// Good - specific exception with meaningful message
throw new IllegalArgumentException("cannot register service name is already assigned:" + serviceName);

// Good - using Objects.requireNonNull for parameter validation
Objects.requireNonNull(eventSourceKey, "eventSourceKey must be non-null");
```

### Null Handling

- Use `Objects.requireNonNull` to validate parameters
- Use Optional for values that might be absent
- Avoid returning null from methods when possible

Example:

```java
// Good - null validation
public void registerEventSource(String sourceName, EventSource<T> eventSource) {
    Objects.requireNonNull(eventSource, "eventSource must be non-null");
    // Implementation
}

// Good - using Optional
public Optional<Service<?>> findService(String name) {
    return Optional.ofNullable(registeredServices.get(name));
}
```

### Resource Management

- Use try-with-resources for automatic resource cleanup
- Close resources in finally blocks if try-with-resources is not applicable

Example:

```java
// Good - try-with-resources
try (FileReader reader = new FileReader(configFileName)) {
    return bootServer(reader, logRecordListener);
}
```

## Logging

### Log Levels

- ERROR: Severe errors that cause the application to malfunction
- WARNING: Potential issues that don't prevent the application from working
- INFO: Important runtime events (startup, shutdown, configuration)
- DEBUG: Detailed information for debugging
- TRACE: Very detailed information for troubleshooting

### Logging Practices

- Use the appropriate log level
- Include relevant context in log messages
- Use parameterized logging to avoid string concatenation
- Log exceptions with their stack traces

Example:

```java
// Good - appropriate log level with context
log.info("registerEventSource name:" + sourceName + " eventSource:" + eventSource);

// Good - logging exception with stack trace
log.warning("could not add eventProcessor:" + name + " to group:" + groupName + " error:" + e.getMessage());
```

### Structured Logging

- Use structured logging for machine-readable logs
- Include key-value pairs in log messages

Example:

```java
// Good - structured logging
log.info("Starting event processor group: '" + groupName + "' for running server");
```

## Testing

### Test Structure

- Use JUnit 5 for testing
- Follow the Arrange-Act-Assert pattern
- One test method should test one scenario
- Test methods should have descriptive names

Example:

```java
@Test
public void testPublish() {
    // Arrange
    EventToQueuePublisher<String> eventToQueue = new EventToQueuePublisher<>("myQueue");
    eventToQueue.setCacheEventLog(true);
    ArrayList<Object> actual = new ArrayList<>();
    OneToOneConcurrentArrayQueue<Object> targetQueue = new OneToOneConcurrentArrayQueue<>(100);
    eventToQueue.addTargetQueue(targetQueue, "outputQueue");

    // Act
    eventToQueue.publish("A");
    targetQueue.drainTo(actual, 100);

    // Assert
    Assertions.assertIterableEquals(List.of("A"), actual);
}
```

### Test Coverage

- Aim for high test coverage (>80%)
- Test both positive and negative scenarios
- Test edge cases and boundary conditions
- Use mocks for external dependencies

### Test Organization

- Test classes should mirror the structure of the main code
- Test classes should be named with the class they test plus "Test"
- Group related tests in nested classes

Example:
```
src/main/java/com/telamin/mongoose/dispatch/EventToQueuePublisher.java
src/test/java/com/telamin/mongoose/dispatch/EventToQueuePublisherTest.java
```

## Performance Considerations

### Memory Management

- Minimize object creation in performance-critical paths
- Use primitive types instead of boxed types when possible
- Be careful with string concatenation in loops
- Consider using object pools for frequently created objects

### Concurrency

- Use thread-safe collections for shared data
- Prefer immutable objects for thread safety
- Use explicit locks only when necessary
- Consider using concurrent data structures from java.util.concurrent

Example:

```java
// Good - using concurrent collections for thread safety
private final ConcurrentHashMap<String, Service<?>> registeredServices = new ConcurrentHashMap<>();
private final Set<Service<?>> registeredAgentServices = ConcurrentHashMap.newKeySet();
```

### Resource Usage

- Close resources properly (connections, files, etc.)
- Use try-with-resources for automatic resource cleanup
- Release resources in the reverse order of acquisition

### Optimization

- Optimize only after profiling
- Document performance-critical code
- Consider time and space complexity of algorithms

## Security Best Practices

### Input Validation

- Validate all input from external sources
- Use parameterized queries for database access
- Sanitize data before using it in logs or output

Example:

```java
// Good - input validation
Objects.requireNonNull(configFileName, "fluxtion config file must be specified by system property: " + CONFIG_FILE_PROPERTY);
```

### Authentication and Authorization

- Use proper authentication mechanisms
- Implement role-based access control
- Validate permissions before performing sensitive operations

### Secure Configuration

- Don't hardcode sensitive information
- Use environment variables or secure vaults for secrets
- Validate configuration before use

### Secure Communication

- Use TLS for network communication
- Validate certificates
- Use secure protocols and cipher suites

## Code Organization

### Package Structure

- Organize packages by feature or layer
- Keep related classes together
- Use subpackages for specialized functionality

Example:
```
com.telamin.mongoose.config - Configuration-related classes
com.telamin.mongoose.dispatch - Event dispatching classes
com.telamin.mongoose.service - Service-related classes
com.telamin.mongoose.service.admin - Administration services
```

### Class Structure

- One class per file
- Keep classes focused on a single responsibility
- Extract common functionality to base classes or utilities
- Use composition over inheritance

### Interface Design

- Design interfaces for consumers, not implementers
- Keep interfaces focused and cohesive
- Use default methods for backward compatibility

Example:

```java
// Good - focused interface with clear purpose
public interface EventSource<T> {
    void subscribe(EventSubscriptionKey<T> eventSourceKey);
    void unSubscribe(EventSubscriptionKey<T> eventSourceKey);
    void setEventToQueuePublisher(EventToQueuePublisher<T> targetQueue);
    
    // Default methods for optional functionality
    default void setEventWrapStrategy(EventWrapStrategy eventWrapStrategy) {
    }
}
```

### Code Reuse

- Extract common code to utility classes
- Use composition to share behavior
- Consider using design patterns for common problems

## Dependency Management

### External Dependencies

- Minimize external dependencies
- Use well-maintained libraries
- Keep dependencies up to date
- Document why dependencies are needed

### Internal Dependencies

- Minimize coupling between components
- Use dependency injection
- Design for testability
- Use interfaces to decouple implementation details

Example:

```java
// Good - dependency injection through constructor
public AbstractEventSourceService(
        String name,
        CallBackType eventToInvokeType,
        Supplier<EventToInvokeStrategy> eventToInokeStrategySupplier) {
    this.name = name;
    this.eventToInvokeType = eventToInvokeType;
    this.eventToInokeStrategySupplier = eventToInokeStrategySupplier;
}

// Good - dependency injection through method
@ServiceRegistered
public void scheduler(SchedulerService scheduler) {
    this.scheduler = scheduler;
}
```

### Version Management

- Use semantic versioning
- Document breaking changes
- Provide migration guides for major version changes