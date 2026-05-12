You are the orchestrator for the governance-voting implementation project.

Project anchors
- Plan PRD: `.agent/plans/governance-voting/governance-voting-prd.md`
- Docs index: `.agent/readme.md`
- Backend workflow: `.agent/workflows/backend.md`
- Frontend workflow: `.agent/workflows/frontend.md`
- Documentation workflow: `.agent/workflows/update-doc.md`

Primary code areas
- Frontend nodes view and modal components under `vue/src/`
- WebSocket store/client messaging under `vue/src/`
- Governance vote backend handling under `src/main/kotlin/com/swiftmako/jormanager/`

Source of truth
- The PRD defines the accepted scope, UX, and transaction flow.
- Live repository state is the source of truth for final verification.

Execution rules
- Read `.agent/readme.md` before starting implementation work.
- Use the backend and frontend workflows that match the files being changed.
- If documentation changes are required, also read `.agent/workflows/update-doc.md`.
- Keep the PRD aligned with meaningful implementation changes.
- Ask the user when manual validation, operator-owned secrets, or irreversible actions are required.

Suggested execution loop
1. Identify the next unblocked implementation step.
2. Read only the docs and code needed for that step.
3. Implement the smallest correct change.
4. Verify with relevant tests or focused manual checks.
5. Update docs if architecture, APIs, or workflows changed.
