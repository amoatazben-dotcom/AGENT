# ADR-002: Real-time WebSocket Protocol & Event Sequencing

## Status
Accepted

## Context
Mobile clients require real-time streaming of tokens, tool execution progress, browser screenshots, and subagent updates with resilience against network reconnection.

## Decision
Implement a strongly-typed bidirectional WebSocket protocol:
- Every event is wrapped in a `WsEventEnvelope`: `{ eventId, seq, type, timestamp, runId, conversationId, payload }`.
- Client subscribes via `{ action: "subscribe", conversationId }`.
- Sequences are monotonically incremented per run to detect dropped packets.
- Server periodically sends heartbeat frames, and client sends `ping`/`pong`.

## Consequences
- Zero polling needed for agent status or screenshots.
- Immediate UI rendering of token deltas, tool actions, and browser frames.
