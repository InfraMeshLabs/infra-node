# InfraMesh Node

Core SDK and common protocol contracts for building **Router** and **Worker** nodes in the **InfraMesh distributed AI inference network**.

`infra-node` provides the shared DTOs, health monitoring, authentication, and integration components used by InfraMesh Console, Router, and Worker implementations.

## Overview

InfraMesh coordinates distributed GPU and CPU resources for AI inference.

```text
                         Client
                            │
                            ▼
                         Console
                            │
                    Routing Strategy
                            │
                ┌───────────┴───────────┐
                │                       │
                ▼                       │
              Router                    │
                │                       │
                └───────────┬───────────┘
                            │
                            ▼
                          Worker
                            │
                            ▼
                        AI Runtime
```

- **Console** — Manages nodes, routing strategies, and inference orchestration.
- **Router** — Optionally selects a Worker from candidates supplied by Console.
- **Worker** — Executes AI inference using a configured runtime.
- **infra-node** — Provides the common SDK and protocol contracts shared between components.

A Router is optional when Console uses a built-in routing strategy.

## Features

- Common Router and Worker protocol contracts
- Shared inference and chat DTOs
- Tool calling contracts
- Node health monitoring
- CPU and memory monitoring
- NVIDIA GPU monitoring
- Node API key authentication
- Servlet and reactive integration
- Spring Boot auto-configuration

## Protocol

`infra-node` acts as the common communication contract between InfraMesh components.

```text
Console
   │
   │ RoutingRequest
   ▼
Router
   │
   │ RoutingResponse
   ▼
Console
   │
   │ WorkerRequest
   ▼
Worker
   │
   ▼
AI Runtime
```

### Router

A Router receives eligible Worker candidates from Console and returns the selected Worker.

Common contracts include:

```text
RoutingRequest
RoutingHints
WorkerCandidate
RoutingResponse
```

The Router does not execute inference or call the selected Worker directly.

### Worker

A Worker receives an inference request and executes it against the configured AI runtime.

Common contracts include:

```text
WorkerRequest
Worker Response
Tool Contracts
```

Workers may integrate runtimes such as:

- Ollama
- vLLM
- OpenAI-compatible runtimes
- Custom AI runtimes

Workers may support both blocking and streaming inference.

```http
POST /api/v1/invoke
POST /api/v1/stream
```

## Session ID

`sessionId` identifies a logical inference session.

It can be propagated through:

```text
Client
   │
   ▼
Console
   │
   ▼
RoutingRequest / WorkerRequest
   │
   ▼
Router / Worker
```

Session creation and Worker affinity are managed by Console.

`infra-node` only defines and transports the session identifier.

## Node Health

Router and Worker nodes can expose runtime health information through:

```http
GET /api/v1/health
X-Infra-Api-Key: <NODE_API_KEY>
```

Health information may include:

- CPU usage
- Memory usage
- GPU utilization
- GPU memory
- Active requests
- Queue size
- Average latency
- Throughput
- Uptime

CPU-only nodes are supported.

## Authentication

InfraMesh nodes can authenticate incoming requests using:

```http
X-Infra-Api-Key: <NODE_API_KEY>
```

Supported authentication modes may include:

```text
API_KEY
NONE
```

`NONE` should only be used in trusted environments.

## Installation

`infra-node` requires Java 17 or later.

### Gradle

```gradle
repositories {
    mavenCentral()
}

dependencies {
    implementation 'io.github.inframeshlabs:inframesh-node:0.1.0'
}
```

### Maven

```xml
<dependency>
    <groupId>io.github.inframeshlabs</groupId>
    <artifactId>inframesh-node</artifactId>
    <version>0.1.0</version>
</dependency>
```

## Local Development

Build the SDK:

```bash
./gradlew build
```

Publish to Maven Local:

```bash
./gradlew publishToMavenLocal
```

Use the local publication from another Gradle project:

```gradle
repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation 'io.github.inframeshlabs:inframesh-node:0.1.0'
}
```

## Requirements

- Java 17+
- Spring Framework / Spring Boot for Spring-specific integrations

## Versioning

`infra-node` follows semantic versioning.

The `0.x` release line represents the active development phase of the SDK. Public APIs and protocol contracts may change before `1.0.0`.

Console, Router, and Worker should use compatible `infra-node` versions because the SDK defines their shared communication contracts.

## Related Projects

| Project | Description |
| --- | --- |
| `infra-console` | Control plane and inference orchestration layer |
| `infra-router` | Reference implementation for Router nodes |
| `infra-worker` | Reference implementations for Worker nodes |

## Status

`infra-node` is currently under active development.

APIs and protocol contracts may change before the first stable `1.0.0` release.

## License

Apache License 2.0
