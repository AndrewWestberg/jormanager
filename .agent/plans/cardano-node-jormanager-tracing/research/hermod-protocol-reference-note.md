# Hermod-Compatible Protocol Reference Note

## Purpose

This note captures which upstream references matter most while implementing the
direct `JorManager <-> node` Hermod-compatible protocol boundary.

The runtime design does **not** depend on running `cardano-tracer`,
`hermod-tracer`, or `hermod-rust` as sidecars. These references exist so the
JorManager implementation can stay wire-compatible with the upstream ecosystem.

## Most Important References

### 1. Wire Protocol

- `trace-forward`
  - <https://github.com/IntersectMBO/cardano-node/tree/master/trace-forward>

Use this as the primary upstream reference for:

- trace-forward protocol concepts
- `TraceObject` transport expectations
- `DataPoint` transport expectations
- how the forwarding protocols sit alongside the Cardano mux model

This is the best anchor for understanding the Haskell-side protocol intent.

### 2. Runtime Consumer Behavior

- `cardano-tracer`
  - <https://github.com/IntersectMBO/cardano-node/tree/master/cardano-tracer>

Use this as the primary upstream reference for:

- how a real external consumer connects to forwarded node data
- lifecycle expectations for long-running trace consumption
- practical behavior around trace and data-point ingestion

This is the best anchor for understanding how an external consumer is expected
to behave, even though JorManager will not run `cardano-tracer` itself.

### 3. Tracing Framework Direction

- `hermod-tracing`
  - <https://github.com/IntersectMBO/hermod-tracing>

Use this as the primary upstream reference for:

- terminology and architecture direction
- the relationship between tracers, forwarding, metrics, and data points
- likely future direction for subscriptions and structured observability

This is the best anchor for understanding where the tracing model is going, not
just where it is today.

### 4. Wire-Compatible Alternative Implementation

- `hermod-rust`
  - <https://github.com/nixedge/hermod-rust>

Use this as the primary upstream reference for:

- a compact, readable implementation of Hermod-compatible protocol roles
- concrete message handling and framing behavior
- acceptor and server behavior without needing to read all Haskell code first

This is the best anchor for implementation-oriented protocol reading.

## Highest-Value Files

### Protocol Shape

- `trace-forward/README.md`
  - <https://github.com/IntersectMBO/cardano-node/blob/master/trace-forward/README.md>

### Hermod Server And Consumer Behavior

- `hermod-rust/src/server/mod.rs`
  - <https://github.com/nixedge/hermod-rust/blob/master/src/server/mod.rs>
- `hermod-rust/config/hermod-tracer.yaml`
  - <https://github.com/nixedge/hermod-rust/blob/master/config/hermod-tracer.yaml>
- `hermod-rust/src/acceptor.rs`
  - <https://github.com/nixedge/hermod-rust/blob/master/src/acceptor.rs>

### Protocol Message Reference

- `hermod-rust/README.md`
  - <https://github.com/nixedge/hermod-rust/blob/master/README.md>

This README is especially useful for quickly locating:

- `MsgTraceObjectsRequest`
- `MsgTraceObjectsReply`
- `MsgDone`
- `TraceObject` field expectations

## Implementation Guidance

When implementing JorManager's direct protocol client, prefer this reading
order:

1. `trace-forward/README.md`
2. `hermod-rust/README.md`
3. `hermod-rust/src/acceptor.rs`
4. `hermod-rust/src/server/mod.rs`
5. `cardano-tracer` sources for consumer behavior details
6. `hermod-tracing` docs for architecture and future-facing semantics

## What Not To Infer

Do not treat these references as approval to:

- add `cardano-tracer` as a runtime dependency
- add `hermod-tracer` as a runtime dependency
- keep or reintroduce EKG HTTP polling
- keep or reintroduce Prometheus HTTP scraping

They are references for compatibility and implementation guidance only.
