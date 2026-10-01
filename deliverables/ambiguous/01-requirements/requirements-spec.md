# Requirements specification

Improve short-link safety and reliability by using a configured public origin, canonicalising self-origin checks, limiting link creation, and adding configurable link expiration with operational auditability.

**Change type:** brownfield

## Functional requirements

- The service shall build LinkResponse.shortUrl and redirect Location headers from the configured public origin, using app.public-origin or the existing server configuration, and shall never derive them from the incoming request Host header.
- The configured public origin shall be validated at startup or configuration binding time; an absent or invalid origin shall fail configuration rather than causing request-time URLs to use the request Host header.
- The existing self-origin URL validation shall compare the destination host with the configured public-origin host after canonicalisation: case folding, removal of trailing dots, and IDN conversion to a canonical ASCII/punycode form. Existing scheme and port comparison behavior shall otherwise be preserved.
- Link creation shall be limited to 30 accepted creation requests per rolling one-minute window per client IP. Requests beyond the limit shall not create a link or consume a short-code-generation attempt.
- Requests rejected by the creation rate limiter shall return HTTP 429 and the existing standard ErrorResponse body format.
- The client IP used for rate limiting shall be determined by the service's configured trusted-proxy behavior; absent trusted-proxy configuration, the direct remote address shall be used and untrusted forwarding headers shall not override it.
- A link created without expiresInDays shall expire 90 days after creation.
- A creation request may provide expiresInDays as an integer from 1 through 365 inclusive. Values outside that range, non-integer values, or malformed values shall be rejected using the existing validation/error response conventions.
- The selected expiration time shall be persisted with the link and shall be based on the service clock so it can be evaluated consistently.
- Existing link-creation behavior, including the existing HTTP 503 response when short-code generation is exhausted, shall remain unchanged except where superseded by the rate limit or expiration validation requirements.
- A redirect request for an expired link shall return HTTP 410 Gone with the existing standard ErrorResponse body format and shall not issue a redirect Location header.
- A redirect request for a non-expired link shall retain existing redirect behavior and shall issue a Location header using the configured public origin only where the existing redirect contract requires a generated short URL; the destination URL itself shall remain the stored validated target.
- A redirect request for a missing link shall retain the existing not-found status and error response behavior.
- Every rate-limited creation request shall produce an audit event in the existing audit trail containing, at minimum, the event type, client IP or privacy-preserving client identifier, timestamp, and outcome, and shall produce a corresponding application log entry.
- Every expired-link access shall produce an audit event in the existing audit trail containing, at minimum, the short code or link identifier, timestamp, request outcome, and expiration-related event type, and shall produce a corresponding application log entry.

## Non-functional requirements

- Origin construction and self-origin comparison shall be deterministic and covered for case variants, trailing-dot variants, and equivalent Unicode/IDN host representations.
- Rate limiting shall be atomic under concurrent requests so that concurrent requests cannot materially exceed the configured limit for one client IP.
- Rate-limit and expiration logs shall use the existing application logging framework, shall be structured consistently with existing logs where supported, and shall not contain credentials, API keys, passwords, or unnecessary sensitive URL data.
- The implementation shall use configuration or environment variables for deployment-specific values and shall not hard-code secrets.
- The expiration and rate-limit behavior shall be observable through HTTP responses, persisted link or audit records, and application logs without requiring implementation-specific internals.
- The change shall preserve existing API behavior for functionality not explicitly changed by this specification.

## Out of scope

- Malicious-URL scanning, reputation checks, or content inspection.
- Blocking destinations in private, loopback, link-local, or other internal network ranges.
- Changing the existing short-code algorithm or changing the existing HTTP 503 behavior when code generation is exhausted.
- Adding user authentication, per-account quotas, or administrative rate-limit management APIs.
- Adding a new analytics model or changing existing analytics semantics.
- Using the incoming request Host header to construct public short URLs or redirect response URLs.

## Open questions, answers and assumptions

| Question | Human answer | Assumption |
|---|---|---|
| What exact configuration property and fallback should provide the public origin? | Human decision requires the configured public origin from app.public-origin/server configuration and prohibits request Host usage. | Use app.public-origin as the primary property and the existing server configuration as the documented fallback. Require one valid absolute origin at startup; do not fall back to the request Host header. |
| Should the 30-per-minute rate limit use a fixed window, sliding window, or token bucket? |  | Use an atomic rolling one-minute window per client IP. The limit is 30 creation requests in that window. |
| How should client IPs be determined when the service is behind a proxy? |  | Use the existing trusted-proxy configuration if present; otherwise use the direct remote address and ignore untrusted forwarding headers. |
| Should expiration be evaluated exactly at the expiration timestamp or only by calendar date? |  | Evaluate expiration at an exact instant: a link is valid before expiresAt and expired at or after expiresAt. The default expiresAt is creation time plus 90 days. |
| Should expiresAt be added to the link-creation response? |  | Persist expiration and enforce it for redirects; expose expiresAt in the creation response if LinkResponse is extended without removing existing fields, so clients can discover the effective expiration. |
| What retention period should apply to audit events for rate limits and expired-link accesses? |  | Use the existing audit repository retention and operational policies; this change does not introduce a separate retention policy. |

