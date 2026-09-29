# Brownfield: analytics and a bug fix on the released service

**Scenario:** BROWNFIELD · **Recording:** `brownfield` · run after the greenfield release is published

## Requirement

Enhance the existing URL shortener:
1. Analytics: record each redirect's timestamp and referrer (Referer header, if any), and add GET /api/links/{code}/analytics that returns, for one link, total clicks, clicks per day for the last 30 days, and the top 5 referrers. Compute these with database queries (count, date-bounded grouping, ordering with a limit), not by loading all events into memory.
2. Bug fix: short links can currently be created for URLs pointing back at the shortener itself, which creates redirect loops. Reject them. The shortener's public origin comes from configuration (app.public-origin), falling back to the server's own configuration; never derive it from the request's Host header.
3. Reject destination URLs longer than 2048 characters with the existing invalid-URL error.
Existing endpoints and their responses must keep working unchanged.

## Why the requirement is this specific

The first brownfield run (recording `brownfield-incident.json`) surfaced these points at human checkpoints: the
analytics path belongs under /api, the origin must not come from the untrusted Host header, and the security
review raised unbounded URLs and in-memory aggregation as HIGH findings. That run was stopped after an
orchestrator bug (stage reverts not scoped to the run) and is kept as an incident record.
