# 📋 Task & Implementation History

> **Store successful PRDs and implementation plans for reference.**

This folder contains Product Requirement Documents (PRDs) and implementation plans from completed features. Use these as templates and reference when implementing similar functionality.

---

## Purpose

Before implementing any feature:

1. **Check for similar past work** — Look for existing plans that match your task
2. **Use as templates** — Follow the same structure and patterns
3. **Ensure consistency** — Maintain architectural decisions across features

---

## How to Use

### Finding Relevant Plans

Plans are organized by feature domain:

```
.agent/task/
├── readme.md (this file)
├── nodes/
│   └── kes-rotation.md
├── wallet/
│   └── transaction-builder.md
└── blocks/
    └── leader-log-calculation.md
```

### Creating New Plans

When starting a new feature:

1. Ask the agent to run **"plan mode"**
2. Review and finalize the plan
3. Save the final plan in this folder under the appropriate domain

**Naming convention:**
```
{domain}/{feature-name}.md
```

---

## Plan Template

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
- Controller: Changes needed
- Service: Changes needed

### Database Changes
Describe any schema changes.

### API Changes  
Describe any new or modified endpoints.

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

**Status:** ✅ Completed | 🚧 In Progress | ❌ Abandoned  
**Date:** YYYY-MM-DD  
**Author:** [Name]
```

---

## Index of Plans

> Update this section as new plans are added.

| Domain | Plan | Status | Date |
|--------|------|--------|------|
| *No plans yet* | — | — | — |

---

## Best Practices

1. **Keep plans updated** — Mark status changes as implementation progresses
2. **Link to PRs** — Reference the pull requests that implemented the plan
3. **Document deviations** — Note any changes from the original plan
4. **Include learnings** — Add retrospective notes for future reference
