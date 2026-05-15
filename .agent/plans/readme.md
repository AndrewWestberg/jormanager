# 📋 Plans & Implementation History

> **Store active and historical PRDs, orchestration prompts, and implementation plans for reference.**

This folder contains Product Requirement Documents (PRDs), execution prompts, task trackers, and supporting research for active work and past features. Use these as templates and references when planning or implementing similar functionality.

---

## Purpose

Before implementing any feature:

1. **Check for similar past work** — Look for existing plans that match your task
2. **Use as templates** — Follow the same structure and patterns
3. **Ensure consistency** — Maintain architectural and process decisions across features

---

## How to Use

### Finding Relevant Plans

Plans are stored in per-plan subfolders, one folder per feature:

```
.agent/plans/
├── readme.md
├── governance-voting/
│   ├── governance-voting-prd.md
│   ├── governance-voting-tasks.json
│   └── prompt.md
└── {future-plan}/
    ├── {future-plan}-prd.md
    ├── {future-plan}-tasks.json
    ├── prompt.md
    ├── research/
    └── task-plans/
```

### Creating New Plans

When starting a new feature:

1. Ask the agent to run **plan mode**
2. Review and finalize the PRD
3. Create a new folder named after the feature
4. Save the PRD, task tracker, and orchestration prompt inside that folder

**Naming convention:**
```
{feature-name}/{feature-name}-prd.md
{feature-name}/{feature-name}-tasks.json
{feature-name}/prompt.md
```

**Required files for new/current plans:**
- New plans must include a matching `*-tasks.json` file next to the PRD.
- New plans should include a `prompt.md` orchestration file when the work spans multiple steps, sessions, or roles.
- Current in-flight plans should keep `*-tasks.json` updated as execution progresses.
- Older historical plans may only have a PRD or PRD plus prompt if they predate the current convention.

### Research Notes

Research notes live in `research/` folders inside each plan directory. Reference them from the relevant PRD when capturing design rationale, implementation gotchas, or validation evidence.

Research note filenames should use a zero-padded numeric prefix so they stay in reading order as the folder grows: `01-topic.md`, `02-topic.md`, `03-topic.md`, and so on.

### Task-Level Planning Docs

For larger efforts, `task-plans/` can hold task-specific planning and review artifacts inside a plan directory. Use this when work is broken down into many tracked subtasks or when a durable review trail is useful.

### Plan Execution Updates

- When executing a plan, update both the PRD and the tasks JSON to reflect changes.
- Keep the plan `Status` section in sync with task progress.
- Update `metadata.updated`, task statuses, and completion notes in `*-tasks.json` after meaningful progress.

### Historical Log Rules

- If a PRD contains `## Status Log`, `## Progress Log`, or another append-only execution/history section, treat it as an append-only chronological transcript.
- Append new entries to the end of the existing log section only.
- Never reorder, rewrite, or delete prior entries to improve grouping.
- If an older entry is incomplete or incorrect, append a corrective entry rather than rewriting history.

---

## PRD Template

When creating a new implementation plan, use this structure:

```markdown
# [Feature Name]

## Overview
Brief description of the feature and its purpose.

## Requirements
- [ ] Requirement 1
- [ ] Requirement 2

## Technical Design

### Components Affected
- Component 1: Changes needed
- Component 2: Changes needed

### Backend Changes
Describe any service, controller, or integration changes.

### Frontend Changes
Describe any view, component, store, or routing changes.

### Data / CLI / Workflow Changes
Describe any schema, command, or operator workflow changes.

## Implementation Steps

1. Step 1
2. Step 2
3. Step 3

## Testing Strategy
How this feature will be tested.

## Rollout Plan
How the feature will be deployed.

## Open Questions
Any unresolved decisions.

---

**Status:** ✅ Completed | 🚧 In Progress | ❌ Abandoned | 📝 Draft
**Date:** YYYY-MM-DD
**Author:** [Name]
```

## Tasks JSON Template

Every active plan must include a `*-tasks.json` file next to its PRD. Omit optional fields when they do not apply.

Task and phase numbering rules:

- Phase IDs should start at `phase-0` and increment by one: `phase-0`,
  `phase-1`, `phase-2`, and so on.
- Task IDs must be grouped by phase number using hundreds-based ranges.
- Phase 0 tasks use `task-001` through `task-099`.
- Phase 1 tasks use `task-100` through `task-199`.
- Phase 2 tasks use `task-200` through `task-299`.
- Phase 3 tasks use `task-300` through `task-399`.
- Continue the same pattern for later phases.
- Task dependencies, critical-path references, prompt references, PRD references,
  and task-plan filenames must all use the same canonical task IDs.
- Use zero-padded numeric IDs for the phase bucket. Suffix variants like
  `task-001a` are allowed only when inserting a small follow-up into an existing
  phase without renumbering the whole graph.

```json
{
  "metadata": {
    "title": "[Feature Name] Tasks",
    "created": "YYYY-MM-DD",
    "updated": "YYYY-MM-DD",
    "version": "1.0.0",
    "totalTasks": 0,
    "prdReference": ".agent/plans/{feature-name}/{feature-name}-prd.md",
    "description": "One-sentence summary of the feature",
    "sourceFiles": ["Optional: key files at risk of change"],
    "importantNote": "Optional: critical constraint or warning"
  },
  "phases": [
    {
      "id": "phase-0",
      "name": "Phase Name",
      "description": "What this phase accomplishes",
      "riskLevel": "low",
      "tasks": [
        {
          "id": "task-001",
          "title": "Short task title",
          "description": "What to implement and why",
          "status": "pending",
          "priority": "high",
          "estimatedHours": 1.0,
          "dependencies": [],
          "targetPath": "path/to/file.ext",
          "completedAt": "YYYY-MM-DD",
          "actualHours": 1.2,
          "completedNotes": "Optional: what was actually done",
          "implementationNotes": ["Optional: step-by-step guidance"],
          "testCases": ["Optional: test cases"],
          "acceptance": ["Optional: acceptance criteria"],
          "notes": "Optional: additional context"
        }
      ]
    }
  ],
  "summary": {
    "totalPhases": 0,
    "totalTasks": 0,
    "estimatedTotalHours": 0,
    "criticalPath": ["task-001"],
    "keyFiles": ["path/to/file1", "path/to/file2"],
    "riskAreas": ["Optional: key risks and unknowns"],
    "testingNotes": "Optional: testing strategy summary",
    "rollbackPlan": "Optional: how to roll back"
  }
}
```

## Prompt Template

For multi-step or long-running work, add `prompt.md` that tells the agent how to execute the plan consistently.

Recommended sections:

```markdown
You are the orchestrator for the [feature-name] implementation project.

Project anchors
- Plan PRD: `.agent/plans/{feature-name}/{feature-name}-prd.md`
- Tasks: `.agent/plans/{feature-name}/{feature-name}-tasks.json`
- Research: `.agent/plans/{feature-name}/research/`
- Task plans: `.agent/plans/{feature-name}/task-plans/`

Relevant workflows
- `.agent/workflows/backend.md`
- `.agent/workflows/frontend.md`
- `.agent/workflows/update-doc.md`

Source of truth
- Tasks JSON for task state and dependencies
- PRD for approved design and scope
- Live repository state for final verification

Execution rules
- Select one unblocked task at a time
- Keep PRD/tasks/docs in sync
- Update research when durable findings are discovered
- Ask the user when human decisions or manual validation are required
```

Use `prompt.md` for orchestration rules and high-discipline execution. Keep feature requirements and design decisions in the PRD, not in the prompt.

---

## Index of Plans

> Update this section as new plans are added or migrated.

| Domain | Plan | Status | Date |
|--------|------|--------|------|
| Node Ops | [Cardano Node And JorManager Tracing Migration](cardano-node-jormanager-tracing/cardano-node-jormanager-tracing-prd.md) | Completed | 2026-05-12 |
| Governance | [Governance Voting](governance-voting/governance-voting-prd.md) | Completed | 2026-01-15 |

---

## Best Practices

1. **Keep plans updated** — Mark status changes as implementation progresses
2. **Use the tasks JSON actively** — It should reflect current execution, not just initial planning
3. **Keep prompts focused** — `prompt.md` should govern execution flow, not duplicate the full PRD
4. **Keep task numbering phase-scoped** — Phase 0 uses `task-001...`, Phase 1 uses `task-100...`, Phase 2 uses `task-200...`, and so on
5. **Document deviations** — Note any meaningful changes from the original plan
6. **Include learnings** — Add retrospective notes or research entries for future reference
