# InfraMesh Node

Core SDK and common protocol contracts for building **Router** and **Worker** nodes in the **InfraMesh distributed AI inference network**.

`infra-node` provides two things:

- **Common Contract** — the shared DTOs and protocol enums used by InfraMesh Console, Router, and Worker implementations.
- **Common Node Infrastructure** — health monitoring, API key authentication, and the **Outbound Connection SDK** that every Router and Worker node reuses instead of implementing its own Console connection.

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
- **infra-node** — Provides the common protocol contracts and the node infrastructure (health, authentication, outbound connection) shared between components.

A Router is optional when Console uses a built-in routing strategy.

## Features

- Common Router and Worker protocol contracts
- Shared inference and chat DTOs
- Tool calling contracts
- Node registration and outbound connection protocol contracts
- Outbound Node Connection SDK (persistent WebSocket connection to Console for Router and Worker nodes)
- Node health monitoring
- CPU and memory monitoring
- NVIDIA GPU monitoring
- Node API key authentication
- Servlet and reactive integration
- Spring Boot auto-configuration

## Protocol

`infra-node` acts as the common communication contract between InfraMesh components.

```text
Common Protocol
├── Routing Protocol
├── Worker Protocol
├── Node Health Protocol
├── Node Registration Protocol
└── Node Connection Protocol
```

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

## Node Registration & Connection Protocol

Console reaches a node in one of two connection modes. Both coexist; `DIRECT` remains the default.

```text
DIRECT     Console ──HTTP──▶ Router / Worker
OUTBOUND   Router / Worker ──Persistent WebSocket──▶ Console
```

`OUTBOUND` lets a node open a persistent connection to Console, so it does not need to expose a
public IP or port. Console-side handling (registration, credential issuance, connection
management, request dispatch) lives in `infra-console`; the node-side connection runtime is the
[Outbound Node Connection](#outbound-node-connection) SDK below.

Contracts:

```text
NodeType                  ROUTER / WORKER
NodeConnectionMode        DIRECT / OUTBOUND
NodeConnectionState       CONNECTED / DISCONNECTED / RECONNECTING
NodeRegistrationRequest   registrationToken, name, nodeType
NodeRegistrationResponse  nodeId, credential
NodeMessageType           CONNECT, CONNECT_ACK, HEARTBEAT, HEARTBEAT_ACK, REQUEST, RESPONSE, ERROR
NodeEnvelope<T>           messageId, type, nodeId, timestamp, requestId, payload
NodeHeartbeat             (empty; heartbeat time is NodeEnvelope.timestamp)
```

Flow:

```text
registrationToken ──▶ Registration ──▶ nodeId + credential
nodeId + credential ──▶ Connection handshake ──▶ NodeEnvelope<NodeHeartbeat> / other messages
```

- The registration token is one-time; reconnects use `nodeId` + `credential`.
- The credential is used only during the connection handshake and is never placed in a `NodeEnvelope`.
- `messageId` identifies the message itself, for debugging and tracing. It is distinct from
  `requestId`, which correlates a request with its response on the connection, which is in
  turn distinct from `sessionId`, which identifies a user inference session.
- `NodeEnvelope.nodeId` is the external identity of the sending node
  (`NodeRegistrationResponse.nodeId`). Once a connection is authenticated to a nodeId, the
  receiving side must verify every `NodeEnvelope.nodeId` on that connection matches — it must
  never trust the value as sent.
- `NodeConnectionState` describes the connection only and is independent of node health
  (`CONNECTED != HEALTHY`).
- A node sends `NodeEnvelope<NodeHeartbeat>` periodically over its connection to signal it is
  still alive.
- A `REQUEST` is answered by exactly one `RESPONSE` (success) or `ERROR` (payload
  `{"message": ...}`) carrying the same `requestId`.

## Outbound Node Connection

`infra-node` provides reusable outbound connection infrastructure for Router and Worker nodes.

It provides:

- WebSocket connection lifecycle
- Node credential authentication
- Heartbeat
- Automatic reconnect
- Exponential backoff and jitter
- Graceful shutdown
- NodeEnvelope transport
- Generic message dispatch

A node implementation only supplies its business logic — inference for a Worker, a routing
decision for a Router. It never implements a WebSocket client, heartbeat, reconnect, or
authentication itself.

```text
                         infra-node
                             │
                  Outbound Connection SDK
                             │
          ┌──────────────────┴──────────────────┐
          │                                     │
     infra-worker                          infra-router
          │                                     │
   Inference Logic                        Routing Logic
          │                                     │
          └──────────────────┬──────────────────┘
                             │
                    Persistent WebSocket
                             │
                             ▼
                        infra-console
```

### Enabling

The SDK is auto-configured (`OutboundNodeConnectionAutoConfiguration`) **only** when
`inframesh.node.connection-mode=OUTBOUND`. With the property absent or set to `DIRECT`, no
connection bean is created and no WebSocket client starts — existing DIRECT nodes need no new
configuration.

Worker and Router use the same `inframesh.node.*` properties:

```yaml
inframesh:
  node:
    connection-mode: OUTBOUND
    console-url: ${INFRAMESH_CONSOLE_URL}      # http(s):// or ws(s)://
    node-id: ${INFRAMESH_NODE_ID}              # NodeRegistrationResponse.nodeId
    credential: ${INFRAMESH_NODE_CREDENTIAL}   # NodeRegistrationResponse.credential

    outbound:
      heartbeat-interval: 10s                  # default 10s

      reconnect:
        initial-delay: 1s                      # default 1s
        max-delay: 30s                         # default 30s
```

### Serving requests

Register a `NodeRequestHandler` bean. The SDK deserializes the `REQUEST` payload into the type you
declare, runs the handler off the WebSocket listener thread, and answers with a `RESPONSE`
(or an `ERROR` if the handler throws or the payload is malformed) carrying the same `requestId`.
The SDK never interprets what the payload means.

```java
// Worker: inference
@Bean
NodeRequestHandler<WorkerRequest, ChatResponse> outboundRequestHandler(WorkerService workerService) {
    return NodeRequestHandler.of(WorkerRequest.class, workerService::invoke);
}

// Router: routing decision
@Bean
NodeRequestHandler<RoutingRequest, RoutingResponse> outboundRequestHandler(RouterService routerService) {
    return NodeRequestHandler.of(RoutingRequest.class, routerService::route);
}
```

The handler may block (for example `.block()` on a reactive service) without delaying heartbeats
or other in-flight requests. A node that defines no `NodeRequestHandler` still connects and
heartbeats; it rejects `REQUEST`s with an `ERROR` so Console does not wait for a timeout.

`NodeMessageHandler` beans receive every other inbound envelope type (`NodeEnvelope<JsonNode>`,
payload left as raw JSON) for protocol messages beyond REQUEST/RESPONSE.

To send an envelope yourself, inject `OutboundNodeConnection` (`connect`, `disconnect`,
`isConnected`, `send(NodeEnvelope)`).

### Behavior

| Concern | Behavior |
| --- | --- |
| Endpoint | `ws(s)://<console-url host>/ws/v1/nodes/connect` (`http`→`ws`, `https`→`wss`) |
| Authentication | `X-Infra-Node-Id` and `X-Infra-Node-Credential` handshake headers. The credential never appears in the URL, a query parameter, a `NodeEnvelope`, a heartbeat, a log line, or an exception message. |
| Lifecycle | Spring `SmartLifecycle`: connects when the context starts. A connection failure never fails startup — it is logged and retried. |
| Heartbeat | `NodeEnvelope<NodeHeartbeat>` every `heartbeat-interval` while connected; stopped on disconnect, restarted after reconnect. |
| Reconnect | Exponential backoff with full jitter: attempt `n` waits a random delay in `[initial-delay, min(initial-delay × 2ⁿ, max-delay)]`. Same `nodeId`/`credential`; never re-registers. Concurrent close/error/handshake-failure events schedule a single reconnect. |
| Connection identity | A late `RESPONSE` for a `REQUEST` received on a connection that has since been replaced is dropped rather than sent over the new connection. Late events from a replaced connection do not affect the current one. |
| Shutdown | Forbid reconnects → cancel a pending reconnect → stop heartbeat → close the socket. A handshake that completes after shutdown began is closed immediately instead of reviving the connection. |

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
