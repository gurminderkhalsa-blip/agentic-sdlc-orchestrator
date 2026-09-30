# Ambiguous: "make links safer"

**Scenario:** AMBIGUOUS · **Recording:** `ambiguous`

## Requirement

Make the short links safer and more reliable for our users.

## What should happen

The requirement does not say what "safer" or "reliable" means. The requirements agent is expected to read it
narrowly; the human sets the scope at the spec checkpoint by rejecting with these answers (the decisions used in
the recorded run):

> Scope for "safer and more reliable":
> 1. Build shortUrl and the Location header from the configured public origin (app.public-origin / server configuration), never from the request Host header.
> 2. Canonicalise hosts in the self-origin check (case, trailing dot, IDN/punycode) so equivalent hosts are rejected.
> 3. Rate-limit link creation to 30 requests per minute per client IP; over the limit return 429 with the standard error body.
> 4. Links expire after 90 days by default; an optional expiresInDays (1-365) can be given at creation; expired links return 410 Gone on redirect.
> 5. No malicious-URL scanning and no blocking of private networks in this change.
> 6. Log and audit rate-limited requests and expired-link accesses in the existing logging and audit trail.
> Keep existing behaviour elsewhere, including HTTP 503 when code generation is exhausted.

and then approving the revised spec with two binding clarifications:

> Approved with two binding clarifications: (a) the client IP for rate limiting is the connection's remote address (request.getRemoteAddr()); X-Forwarded-For is not trusted in this change. (b) Links created before this change have no expiry and never expire; only newly created links get the 90-day default. The rate limit is per application instance (no shared store), as specified.
