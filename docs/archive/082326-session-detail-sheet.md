# Session detail sheet

Date: 2026-08-23

Goal: let users inspect a session without sacrificing the main dashboard's compact information density.

## Design

- [x] Define active and recent session detail hierarchy using only the existing mobile payload.
- [x] Use a Material 3 modal bottom sheet with clear dismissal, drag behavior, and accessible touch targets.
- [x] Preserve the bandwidth-conscious contract; do not add server fields solely for this sheet.

## Implementation

- [x] Make active session cards and recent session rows tappable with full-surface touch targets.
- [x] Show source, repository, title, model, branch, phase/action, elapsed or last-updated time, and activity where available.
- [x] Show todo progress and individual todo items for active sessions.
- [x] Show token and turn statistics with provider-appropriate labeling.
- [x] Show session-scoped lifecycle events when available.
- [x] Handle sparse recent-session summaries without empty or misleading sections.

## Verification

- [x] Build, install, interact with, and visually inspect the sheet only on `100.64.0.7`.
- [x] Verify active and recent session sheets, scrolling, dismissal, and narrow-screen layout.
- [x] Confirm SSE, foreground service, notifications, and dashboard behavior remain intact.
- [x] Copy the verified APK back with SHA-256 `2a3d7de50ebecbd4673e20212234ee06d29a673f39399fa98983c6f94b604758`.
- [x] Review the final diff and move this plan to `docs/archive/` when complete.
