# InfraMesh Node

Core SDK, protocol contracts, and integrations for connecting **Router** and **Worker** nodes to the **InfraMesh distributed AI inference network**.

`infra-node` defines the common communication contracts and node integration components shared by InfraMesh Console, Router, and Worker implementations.

It provides the DTOs, health contracts, authentication support, monitoring components, and integration infrastructure required to build custom InfraMesh nodes.

---

# Overview

InfraMesh is a distributed AI inference platform designed to coordinate heterogeneous compute resources such as GPUs and CPUs across independently deployable nodes.

The primary components are:

```text id="ezp97a"
                         Client
                            │
                            ▼
                     Infra Console
                            │
                   Routing Strategy
                            │
               ┌────────────┴────────────┐
               │                         │
               │ Router-based            │ Built-in
               │ Routing                 │ Routing
               ▼                         │
         Infra Router                    │
               │                         │
               │ workerId                │
               └────────────┬────────────┘
                            │
                            ▼
                       Infra Worker
                            │
                            ▼
                        AI Runtime
```

Each component has a clear responsibility:

- **Console** — Manages organizations, teams, nodes, routing strategies, runtime state, and inference orchestration.
- **Router** — Optionally selects an appropriate Worker from candidates supplied by Console.
- **Worker** — Executes AI inference using a configured AI runtime.
- **infra-node** — Defines the common SDK, protocol contracts, health specifications, authentication integration, and node integration components.

Router and Worker implementations remain independently deployable.

A Router is optional when Console uses one of its built-in routing strategies.

---

# Role of infra-node

`infra-node` acts as the common contract between InfraMesh components.

```text id="zcphhz"
                       infra-node
                           │
                Common Protocol / SDK
                           │
          ┌────────────────┼────────────────┐
          │                │                │
          ▼                ▼                ▼
       Console           Router           Worker
```

Rather than allowing each project to define its own incompatible request and response models, `infra-node` provides the shared contracts used across component boundaries.

Conceptually:

```text id="5f4c04"
Console
   │
   │ RoutingRequest
   ▼
Router
   │
   │ RoutingResponse
   ▼
Console


Console
   │
   │ WorkerRequest
   ▼
Worker
   │
   │ Inference Response
   ▼
Console
```

The corresponding `infra-node` version should be treated as the source of truth for these communication contracts.

---

# Features

`infra-node` provides common components for building Router and Worker nodes that participate in the InfraMesh network.

Major areas include:

- Router protocol contracts
- Worker protocol contracts
- Common chat and inference DTOs
- Tool-related contracts
- Node health contracts
- Node authentication
- CPU and memory monitoring
- NVIDIA GPU monitoring
- Runtime status reporting
- Servlet integration
- Reactive integration
- Common node configuration

---

# Common Protocol

`infra-node` defines the communication boundary between InfraMesh components.

The SDK should contain protocol DTOs rather than Console-specific domain entities.

For example:

```text id="4y75xg"
infra-node
│
├── Common Request / Response
│
├── Router Protocol
│   ├── RoutingRequest
│   ├── RoutingHints
│   ├── WorkerCandidate
│   └── RoutingResponse
│
├── Worker Protocol
│   ├── WorkerRequest
│   └── Worker Response
│
├── Tool Contracts
│
├── Health Contracts
│
└── Authentication / Integration
```

Console, Router, and Worker projects should reuse these contracts instead of defining incompatible copies.

---

# Router Protocol

Router integrations define the common protocol required to connect custom routing implementations to InfraMesh.

A Router does not execute inference.

It receives a routing request containing request information and eligible Worker candidates, selects one of those Workers, and returns the routing result.

```text id="8fchvq"
Console
   │
   │ RoutingRequest
   │
   │ + WorkerCandidates
   ▼
 Router
   │
   │ Worker Selection
   ▼
RoutingResponse
   │
   │ workerId
   ▼
Console
```

The Router does **not** call the selected Worker.

After receiving and validating the routing result, Console invokes the selected Worker.

```text id="tx5ygi"
Console
   │
   ▼
Router
   │
   │ workerId
   ▼
Console
   │
   ▼
Worker
   │
   ▼
AI Runtime
```

---

# RoutingRequest

`RoutingRequest` represents the information supplied to a Router when Console delegates Worker selection.

The exact structure is defined by the current `infra-node` version.

Conceptually it can contain:

```text id="58ovv3"
RoutingRequest
├── Request information
│   ├── Messages
│   ├── Options
│   ├── Tool information
│   └── Other request metadata
│
├── RoutingHints
│
└── WorkerCandidates
```

Router implementations should preserve and use the common request contract rather than introducing incompatible Router-specific request DTOs.

---

# RoutingHints

`RoutingHints` describes routing requirements and preferences associated with the current request.

Depending on the current protocol, hints may represent concepts such as:

```text id="h3yfxz"
Preferred Model
Required Model

Preferred Provider
Required Provider

Required Capabilities
```

Routing hints describe **what the request needs or prefers**.

They do not represent Worker runtime state.

```text id="2p3jgo"
RoutingHints
     │
     └── Request requirements / preferences


WorkerCandidate
     │
     └── Worker capabilities / runtime state
```

This separation allows Router implementations to compare request requirements with Worker capabilities.

---

# WorkerCandidate

`WorkerCandidate` represents a Worker that Console has already determined is eligible for the current routing request.

```text id="ux7zof"
Console

Team Workers
     │
     ▼
Eligibility Filtering
     │
     ▼
WorkerCandidates
     │
     ▼
Router
```

Console is responsible for determining candidate eligibility.

This may include conditions such as:

- Team assignment
- Enabled state
- Drain mode
- Online state
- Streaming support
- Other platform-level eligibility rules

The Router does not independently discover Workers from Console infrastructure.

---

# Worker Candidate Information

A `WorkerCandidate` can expose information useful for custom routing decisions.

Depending on the current protocol, this may include:

```text id="29lnfk"
Identity
├── Worker ID
└── Name

Capabilities
├── Models
├── Provider / Runtime
└── Other capabilities

Runtime
├── Framework
├── Framework Version
├── Uptime
├── Active Requests
├── Queue Size
├── Average Latency
├── Throughput
└── Error Information

Resources
├── CPU
├── Memory
├── GPU
└── VRAM
```

Router implementations may use any subset of this information.

For example:

```text id="o6pab5"
RoutingRequest
      │
      ├── preferredModel = qwen
      └── requiredProvider = VLLM

WorkerCandidates
      │
      ├── Worker A
      │     ├── qwen
      │     ├── VLLM
      │     └── GPU 45%
      │
      └── Worker B
            ├── llama
            ├── Ollama
            └── GPU 20%

              │
              ▼

            Router

              │
              ▼

           Worker A
```

The actual routing algorithm remains outside the SDK.

---

# Router Implementation Independence

`infra-node` defines the Router protocol but does not dictate how Worker selection must be implemented.

A Router can use:

- AI / LLM-based routing
- Rule-based routing
- Score-based routing
- Least-latency routing
- Least-busy routing
- Model-aware routing
- Provider-aware routing
- Hardware-aware routing
- Cost-aware routing
- Organization-specific routing
- Hybrid routing strategies

Conceptually:

```text id="y9q2g8"
RoutingRequest
      │
      ▼
Custom Router
      │
      ├── Rules
      ├── Scores
      ├── AI / LLM
      ├── ML
      └── Custom Logic
      │
      ▼
RoutingResponse
```

Using an AI model inside a Router is optional.

The Router protocol itself is implementation-neutral.

---

# Router Invoke

Router selection is performed through:

```http id="tg87qp"
POST /api/v1/invoke
```

Routing is not a streaming operation.

Even when the original inference request requires streaming:

```text id="plzpcd"
Streaming Request
       │
       ▼
Console
       │
       │ Streaming-capable
       │ WorkerCandidates
       ▼
Router /invoke
       │
       ▼
workerId
       │
       ▼
Console
       │
       ▼
Worker /stream
```

A Router does not require `/api/v1/stream`.

---

# Worker Protocol

Worker integrations define the common protocol required to expose an AI inference runtime as an InfraMesh Worker.

```text id="zbb2ql"
Infra Console
       │
       │ WorkerRequest
       ▼
     Worker
       │
       ▼
 Runtime Adapter
       │
       ▼
   AI Runtime
```

A Worker is responsible for executing inference.

The Worker does not make routing decisions.

---

# WorkerRequest

Worker inference requests use the common Worker request contract defined by `infra-node`.

Conceptually:

```text id="3u8uln"
WorkerRequest
├── Messages
├── Options
├── Tool Definitions
├── Tool Choice
└── Other inference information
```

The exact structure is defined by the installed `infra-node` version.

Worker implementations should accept this common contract at their InfraMesh boundary and translate it into the protocol expected by their AI runtime.

---

# Worker Runtime Integration

Workers act as adapters between the InfraMesh Worker protocol and AI runtimes.

```text id="i5cg28"
                  WorkerRequest
                       │
                       ▼
                 Infra Worker
                       │
           Runtime Protocol Mapping
                       │
         ┌─────────────┼─────────────┐
         ▼             ▼             ▼
      Ollama          vLLM         Custom
         │             │             │
         ▼             ▼             ▼
      AI Model       AI Model      AI Model
```

Possible Worker integrations include:

- Spring AI
- Ollama
- vLLM
- OpenAI-compatible runtimes
- Custom HTTP runtimes
- Local LLM runtimes
- Enterprise AI platforms

`infra-node` does not require every Worker to use the same framework or runtime.

---

# External Protocol Independence

The Worker protocol is independent from external client protocols.

For example:

```text id="w76gu8"
OpenAI-Compatible Client
          │
          │ OpenAI Protocol
          ▼
       Console
          │
          │ WorkerRequest
          ▼
       Worker
          │
          │ Runtime Protocol
          ▼
      AI Runtime
```

Therefore, supporting an OpenAI-compatible client does not require every Worker to expose:

```text id="7rks2s"
/v1/chat/completions
```

directly.

External protocol translation can occur at the Console boundary while Workers continue using the InfraMesh Worker protocol.

---

# Worker Invoke

Blocking Worker inference can be exposed through:

```http id="d85jxk"
POST /api/v1/invoke
```

Conceptually:

```text id="u8cjlk"
WorkerRequest
       │
       ▼
Worker
       │
       ▼
AI Runtime
       │
       ▼
Inference Response
```

---

# Worker Streaming

Workers may optionally support streaming through:

```http id="gg9mxq"
POST /api/v1/stream
```

Conceptually:

```text id="j3fj5b"
WorkerRequest
       │
       ▼
Worker
       │
       ▼
AI Runtime
       │
       ├── Chunk
       ├── Chunk
       ├── Chunk
       └── Chunk
       │
       ▼
Streaming Response
```

Streaming capability belongs to the Worker.

Router implementations do not proxy or execute Worker streams.

---

# Tool Contracts

`infra-node` can define common tool-related contracts used to preserve tool calling information across InfraMesh boundaries.

Conceptually:

```text id="vgj5kp"
Client / Agent
      │
      ▼
Console
      │
      ▼
WorkerRequest
├── Messages
├── Tools
└── Tool Choice
      │
      ▼
Worker
      │
      ▼
AI Runtime
```

The Worker can translate common tool definitions into the format expected by the runtime.

When a runtime returns a tool call:

```text id="zwm66e"
AI Runtime
    │
    ▼
Tool Call
    │
    ▼
Worker
    │
    ▼
InfraMesh Response
```

the Worker translates the result back into the common InfraMesh contract.

The SDK defines the protocol.

It does not require the Worker itself to execute application-specific tools.

---

# Node Health

Router and Worker nodes can expose health information using the common health contract provided by `infra-node`.

```http id="o3cmxe"
GET /api/v1/health
X-Infra-Api-Key: <NODE_API_KEY>
```

Conceptually:

```text id="ogvdhu"
                 Infra Console
                      │
             ┌────────┴────────┐
             ▼                 ▼
          Router             Worker
             │                 │
             ▼                 ▼
        Node Health        Node Health
             │                 │
             └────────┬────────┘
                      ▼
                  NodeRuntime
```

This allows Console to maintain runtime information for independently deployed nodes.

---

# Health Information

Depending on the current health contract and runtime environment, node health information may include:

```text id="47hgtz"
Status

Runtime
├── Framework
├── Framework Version
└── Uptime

Load
├── Active Requests
├── Queue Size
├── Average Latency
├── Throughput
└── Error Information

CPU
├── Information
└── Usage

Memory
├── Total
├── Used
├── Available
└── Usage

GPU
├── Index
├── Name
├── Usage
├── Total Memory
├── Used Memory
└── Available Memory
```

Not every node or environment is required to expose every hardware metric.

CPU-only nodes are supported.

---

# GPU Monitoring

`infra-node` can collect NVIDIA GPU information when a supported NVIDIA environment is available.

GPU information may include:

```text id="s13mco"
GPU
├── Index
├── Name
├── Usage
├── Total Memory
├── Used Memory
└── Available Memory
```

GPU monitoring may use `nvidia-smi` when available.

On environments without NVIDIA GPU support, GPU information can remain empty according to the health contract.

GPU support is not required for a node to participate in InfraMesh.

---

# CPU and Memory Monitoring

`infra-node` can provide common system monitoring components for collecting CPU and memory information.

This allows Router and Worker implementations to expose consistent runtime health information without implementing platform monitoring independently.

Conceptually:

```text id="drakz6"
Operating System
      │
      ▼
infra-node
System Monitoring
      │
      ├── CPU
      ├── Memory
      └── GPU
      │
      ▼
NodeHealthResponse
```

---

# Authentication

InfraMesh nodes can authenticate incoming requests using a node API key.

```http id="3ypk9d"
X-Infra-Api-Key: <NODE_API_KEY>
```

The API key is issued and managed by Infra Console.

Depending on deployment configuration, supported authentication modes may include:

```text id="iqtxuo"
API_KEY
NONE
```

`NONE` should only be used in trusted environments.

Authentication behavior should remain consistent between Router and Worker integrations where possible.

---

# Node Integration

`infra-node` provides common integration components so applications can participate in InfraMesh without implementing every infrastructure concern independently.

Conceptually:

```text id="3q3ojb"
Custom Application
       │
       ▼
    infra-node
       │
       ├── Protocol
       ├── Authentication
       ├── Health
       ├── Monitoring
       └── Configuration
       │
       ▼
    InfraMesh
```

Applications remain responsible for their actual routing or inference implementation.

---

# Servlet and Reactive Integration

Different node implementations may use different application stacks.

For example:

```text id="jgz81e"
Blocking Worker
    │
    └── Servlet / Spring MVC


Streaming Worker
    │
    └── Reactive / Netty


Router
    │
    └── Implementation-specific
```

`infra-node` can provide integrations appropriate for these environments without requiring every node implementation to use the same web stack.

Applications should avoid unnecessarily combining Servlet and reactive stacks when only one is required.

---

# Project Structure

The SDK is organized around common contracts and node-specific integrations.

The exact package structure should follow the actual source tree, but conceptually:

```text id="vwwzdn"
infra-node
└── src/main/java/com/inframesh/node
    │
    ├── dto
    │   ├── common
    │   ├── router
    │   └── worker
    │
    ├── config
    │
    ├── security
    │
    ├── health
    │
    ├── monitoring
    │
    └── integration
        ├── servlet
        └── reactive
```

Router protocol DTOs belong under the Router contract area.

Worker protocol DTOs belong under the Worker contract area.

Health, authentication, and monitoring components that apply to both node types should remain common where practical.

The exact package structure may evolve as the SDK grows.

---

# What infra-node Does Not Own

`infra-node` defines contracts and reusable integration infrastructure.

It does **not** own application-level platform state.

The SDK should not contain Console domain entities such as:

```text id="r4bgzt"
Organization
Team
TeamNode
Node database entities
Worker database entities
NodeRuntime database entities
```

It should also not contain Console persistence repositories.

Similarly, the SDK does not define how a custom Router must select a Worker or how a Worker must implement its AI runtime.

```text id="rhwmkk"
infra-node
     │
     ├── Defines protocol
     ├── Defines integration contracts
     └── Provides reusable infrastructure


Console
     │
     └── Owns platform state


Router
     │
     └── Owns routing algorithm


Worker
     │
     └── Owns inference implementation
```

---

# Versioning

Console, Router, and Worker communicate using contracts provided by `infra-node`.

Protocol changes should therefore be treated carefully.

For example, changes to:

```text id="j3z3ke"
RoutingRequest
RoutingHints
WorkerCandidate
RoutingResponse
WorkerRequest
Worker Response
NodeHealthResponse
Tool Contracts
```

may affect multiple InfraMesh components.

The `infra-node` version should clearly identify the protocol version used by each component.

As the project approaches stable releases, backward compatibility and protocol versioning policies should be defined explicitly.

---

# Installation

The project is currently under development.

During local development, the SDK can be built as a JAR:

```bash id="30j0nb"
./gradlew build
```

The generated artifact can be found under:

```text id="rfuipm"
build/libs/
```

For local integration testing, the JAR can be added directly to another Gradle project:

```gradle id="7hnh42"
dependencies {
    implementation files('libs/infra-node-1.0.0.jar')
}
```

Repository-based dependency distribution can later replace the local JAR dependency.

---

# Requirements

- Java 17+
- Gradle
- Spring Framework / Spring Boot for Spring-specific integrations

Optional runtime requirements depend on the integration being used.

For example, NVIDIA GPU monitoring requires an environment where NVIDIA GPU information can be collected.

The protocol DTOs themselves should remain as independent from runtime implementation details as practical.

---

# Design Principles

InfraMesh Node follows several core principles.

## Protocol First

`infra-node` is the common communication contract between InfraMesh components.

Console, Router, and Worker should reuse these contracts rather than maintaining incompatible copies.

## Runtime Independent

Workers can integrate different AI runtimes without changing the InfraMesh control plane or Router protocol.

## Router Implementation Independent

The Router protocol defines inputs and outputs without dictating the internal routing algorithm.

AI, rules, scoring, ML, or custom logic can all be used.

## Heterogeneous Compute

GPU and CPU-only Workers can participate in the same InfraMesh network.

## Lightweight Integration

Applications should be able to participate in InfraMesh by adding the SDK and implementing only the required routing or inference logic.

## Separation of Responsibilities

```text id="olqxsb"
Console
    → Platform state
    → Worker eligibility
    → Routing orchestration
    → Worker invocation

Router
    → Worker selection

Worker
    → AI inference

infra-node
    → Common protocol
    → Health
    → Authentication
    → Integration infrastructure
```

## Independent Deployment

Router and Worker implementations can be deployed independently from Console and from each other.

## Vendor Neutral

The SDK does not require a particular AI model provider, runtime, or framework.

---

# Component Contracts

The overall contract can be summarized as:

```text id="th2h4h"
                        infra-node
                            │
            ┌───────────────┴───────────────┐
            │                               │
            ▼                               ▼

      Router Contract                 Worker Contract

      RoutingRequest                  WorkerRequest
      RoutingHints                         │
      WorkerCandidate                      ▼
           │                           AI Runtime
           ▼                               │
         Router                            ▼
           │                         Worker Response
           ▼
      RoutingResponse
```

Console uses both contracts to orchestrate inference without coupling Router and Worker implementations directly.

---

# Status

`infra-node` is currently under active development.

APIs, package structures, protocol DTOs, configuration properties, health contracts, and integration components may change before the first stable release.

During this stage, the installed `infra-node` version should be treated as the source of truth for Console, Router, and Worker communication.

---

# License

Apache License 2.0
