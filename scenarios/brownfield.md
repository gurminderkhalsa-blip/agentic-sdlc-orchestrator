# Brownfield: analytics and a bug fix on the released service

**Scenario:** BROWNFIELD · **Recording:** `brownfield` · run after the greenfield release is published

## Requirement

Enhance the existing URL shortener:
1. Analytics: record each redirect's timestamp and referrer (Referer header, if any), and add an endpoint that returns, for one link, total clicks, clicks per day for the last 30 days, and the top 5 referrers.
2. Bug fix: short links can currently be created for URLs pointing back at the shortener itself, which creates redirect loops. Reject them.
Existing endpoints and their responses must keep working unchanged.
