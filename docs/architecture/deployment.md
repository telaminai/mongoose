# Mongoose server Deployment Guide

## Introduction

This document provides guidance on deploying Mongoose server in various environments. It covers deployment considerations, performance tuning, and best practices to ensure optimal operation of Mongoose server applications.

## Deployment Models

Mongoose server can be deployed in several ways, depending on the requirements of your application:

### Standalone Application

Mongoose server can be deployed as a standalone Java application. This is the simplest deployment model and is suitable for many use cases.

#### Configuration:
```java
// Load configuration from a file
MongooseServer server = MongooseServer.bootServer(logRecordListener);

// Or programmatically
MongooseServerConfig mongooseServerConfig = new MongooseServerConfig();
// Configure mongooseServerConfig
MongooseServer server = MongooseServer.bootServer(mongooseServerConfig, logRecordListener);
```

### Embedded in Another Application

Mongoose server can be embedded within another Java application, such as a Spring Boot application or a web server.

#### Configuration:
```java
// Create and configure the server
MongooseServerConfig mongooseServerConfig = new MongooseServerConfig();
// Configure mongooseServerConfig
MongooseServer server = new MongooseServer(mongooseServerConfig);

// Register components
server.registerEventSource("source1", eventSource);
server.addEventProcessor("processor1", "group1", idleStrategy, () -> eventProcessor);

// Initialize and start
server.init();
server.start();
```

### Containerized Deployment

Mongoose server can be deployed in containers such as Docker, which provides isolation and portability.

#### Dockerfile Example:
```dockerfile
FROM openjdk:11-jre-slim

WORKDIR /app

COPY target/fluxtion-server.jar /app/
COPY config/server-config.yaml /app/config/

ENV JAVA_OPTS="-Xmx2g -Xms1g"
ENV CONFIG_FILE="/app/config/server-config.yaml"

CMD ["java", "-jar", "fluxtion-server.jar", "-Dfluxtionserver.config.file=${CONFIG_FILE}"]
```

### Cloud Deployment

Mongoose server can be deployed to cloud environments such as AWS, Azure, or Google Cloud Platform.

#### Considerations:
- Use managed services for monitoring and logging
- Configure auto-scaling based on load
- Use cloud-native storage for persistence
- Implement proper security measures

## Performance Considerations

### Memory Configuration

Proper memory configuration is essential for optimal performance:

```
-Xmx<size>  # Maximum heap size
-Xms<size>  # Initial heap size
-XX:MaxMetaspaceSize=<size>  # Maximum metaspace size
```

Recommended settings:
- Set `-Xms` and `-Xmx` to the same value to avoid heap resizing
- Monitor memory usage and adjust as needed
- Consider using G1GC for large heaps: `-XX:+UseG1GC`

### Thread Configuration

Mongoose server uses a threading model based on agents. Configure threads appropriately:

```yaml
threadConfig:
  - name: "default"
    idleStrategy: "BusySpin"
  - name: "lowLatency"
    idleStrategy: "BusySpin"
  - name: "highThroughput"
    idleStrategy: "BackOff"
```

Considerations:
- Use `BusySpin` for low-latency requirements
- Use `BackOff` for high-throughput scenarios
- Balance the number of threads with available CPU cores

### Queue Sizing

Configure queue sizes based on expected load:

```java
// In EventFlowManager.java
new ManyToOneConcurrentArrayQueue<T>(queueSize);
```

Recommendations:
- Start with a queue size of 1024 or 2048
- Monitor queue overflow and adjust as needed
- Consider using different queue sizes for different event types

## Availability and recovery

A Mongoose server is one JVM. What it supports today for keeping a deployment available:

- **Supervised restart.** Run the server under a process supervisor (systemd, a Kubernetes Deployment, a container
  restart policy) so a failed JVM is restarted. Make startup fail fast when a critical service, feed or processor
  does not start (see the [operations runbook](../reference/operations-runbook.md)), so a bad deployment exits
  instead of running half-started.
- **Health checks.** `MongooseHealthService` aggregates the built-in and your own checks; expose it to your
  supervisor or load balancer for liveness and readiness.
- **Rebuilding state.** Mongoose does not replicate or snapshot processor state. Rebuild it on restart from your own
  source of truth (a database, a venue's state, an event log you keep), or replay a recorded run into a processor
  ([record and replay](../example/how-to/how-to-record-and-replay-a-processor.md)) to reproduce or verify state.
- **Running more than one instance.** You can run several independent servers, each with its own feeds and
  processors. Mongoose does not coordinate them: partitioning the work, electing an active instance and moving load
  are up to your deployment.

### Not yet supported (direction)

The following are directions, not features. Do not plan a deployment that depends on them:

- **Clustering**: active-passive or active-active groups of servers managed by Mongoose.
- **State replication**: Mongoose-managed replication or snapshots of processor state between instances.
- **Failover**: Mongoose-managed detection of a failed instance and hand-over of its work.

## Monitoring and Observability

### Metrics Collection

Collect metrics to monitor the health and performance of your Mongoose server:

1. **JVM Metrics**: Heap usage, GC activity, thread count
2. **Application Metrics**: Event throughput, processing latency, queue sizes
3. **System Metrics**: CPU usage, memory usage, disk I/O, network I/O

### Logging

Configure appropriate logging to aid in troubleshooting:

```java
// Set log level for event processors
eventProcessor.setAuditLogLevel(EventLogControlEvent.LogLevel.INFO);
```

Recommendations:
- Use structured logging for easier parsing
- Configure appropriate log levels for different components
- Rotate logs to manage disk space

### Tracing across processes

Mongoose has no built-in distributed tracing (trace or span propagation, Jaeger/Zipkin export); it is not yet
supported. Within one server the audit log records each event cycle. For work that leaves the process, carry your
own correlation id on the events you publish and log it at each boundary.

## Security Considerations

### Authentication and Authorization

Implement proper authentication and authorization:

1. **API Security**: Secure admin APIs with authentication
2. **Role-Based Access**: Restrict access based on roles
3. **Secure Communication**: Use TLS for communication between components

### Data Protection

Protect sensitive data:

1. **Encryption**: Encrypt sensitive data at rest and in transit
2. **Data Masking**: Mask sensitive data in logs
3. **Access Control**: Restrict access to sensitive data

### Network Security

Secure the network:

1. **Firewalls**: Restrict network access
2. **VPNs**: Use VPNs for secure communication
3. **Network Segmentation**: Isolate components in different network segments

## Configuration Management

### Configuration Files

Manage configuration files effectively:

1. **Version Control**: Store configuration files in version control
2. **Environment-Specific Configs**: Use different configurations for different environments
3. **Configuration Validation**: Validate configuration before deployment

### Dynamic Configuration

Change a running processor's configuration by delivering the change as an event on the processor's own agent
thread: publish it on a feed the processor subscribes to, or register an admin command from the processor
([how to write an admin command](../example/how-to/how-to-write-an-admin-command.md)), which Mongoose runs in the
processor's event cycle. Never call a running processor's `onEvent` from another thread: a processor is not
thread-safe, and the call would race its agent thread.

## Deployment Checklist

Before deploying Mongoose server to production, ensure:

1. **Performance Testing**: Test performance under expected load
2. **Security Review**: Review security measures
3. **Monitoring Setup**: Set up monitoring and alerting
4. **Backup Strategy**: Implement backup and recovery procedures
5. **Documentation**: Document deployment procedures and configurations
6. **Rollback Plan**: Prepare a rollback plan in case of issues

## Conclusion

Deploying Mongoose server requires careful consideration of performance, availability and recovery, monitoring, and security. By following the guidelines in this document, you can ensure a successful deployment that meets your application's requirements.

Remember that deployment is not a one-time activity but an ongoing process. Continuously monitor, evaluate, and improve your deployment to maintain optimal performance and reliability.