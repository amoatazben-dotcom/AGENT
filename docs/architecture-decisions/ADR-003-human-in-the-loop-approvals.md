# ADR-003: Human-in-the-Loop Approval Architecture

## Status
Accepted

## Context
High-risk tools (file deletion, arbitrary shell commands, financial transactions) pose safety hazards if executed without explicit user consent.

## Decision
- Implement a deterministic Policy Engine in the Tool Runtime independent of LLM decisions.
- Risk levels: `low`, `medium`, `high`, `critical`.
- If an agent's policy is `require_approval` or the tool risk level is `high`/`critical`, the coordinator pauses execution:
  - Transition run state to `waiting_for_approval`.
  - Persist an `Approval` record with tool name, arguments, risk level, and explanation.
  - Broadcast `approval.created` via WebSocket to the Android client.
  - Block tool execution until the user submits `{ decision: "approved" | "rejected" }`.
  - If approved, resume the agent loop immediately. If rejected, provide feedback to the LLM.

## Consequences
- Prevents autonomous prompt-injection attacks from executing destructive operations.
- Full transparency with audit trails for every security decision.
