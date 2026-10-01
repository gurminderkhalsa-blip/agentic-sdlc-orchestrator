# HTTP API

The service exposes four endpoints. Successful JSON responses use the existing response shapes with the effective `expiresAt` field added to link responses. Errors use the standard RFC 7807 `ProblemDetail` format.

The examples assume `app.public-origin` is configured as `https://short.example.com`. Replace `Ab3xYz9` with a code returned by the create operation.

## `POST /api/links`

Creates a short link.

### Request

```bash
curl -i -X POST http://localhost:8080/api/links \
  -H 'Content-Type: application/json' \
  -d '{"url":"https://example.com/path?q=1","expiresInDays":30}'
```

```json
{
  "url": "https://example.com/path?q=1",
  "expiresInDays": 30
}
```

`url` is required, nonblank, absolute, and must use HTTP or HTTPS. `expiresInDays` is optional; omission selects 90 days. A supplied value must be a JSON integer from 1 through 365 inclusive. Fractional JSON numbers and strings are invalid; for example, both `{"expiresInDays":1.5}` and `{"expiresInDays":"1"}` return `400 Bad Request`.

The expiration instant is calculated from the service clock and persisted with the link. Destinations whose host is equivalent to the configured public-origin host after case folding, trailing-dot removal, or IDN/punycode canonicalisation are rejected when they target the shortener's self-origin redirect space. No reachability, malware, or private-network scan is performed.

### Success — `201 Created`

```json
{
  "code": "Ab3xYz9",
  "shortUrl": "https://short.example.com/Ab3xYz9",
  "originalUrl": "https://example.com/path?q=1",
  "clickCount": 0,
  "expiresAt": "2026-10-31T12:00:00Z"
}
```

`shortUrl` is built only from the configured `app.public-origin`; the request `Host` header is never used. `expiresAt` is an ISO-8601 timestamp for new links.

### Errors

Malformed JSON, blank or malformed URLs, self-origin destinations, and invalid expiration values return `400 Bad Request`:

```http
HTTP/1.1 400 Bad Request
Content-Type: application/problem+json
```

```json
{
  "type": "about:blank",
  "title": "Bad Request",
  "status": 400,
  "detail": "request body is invalid"
}
```

The detail for URL validation failures uses the service's existing invalid-URL message. Values `0`, `366`, negative values, fractional values, strings, and malformed values are validation errors.

More than 30 admitted creation requests from one direct remote address in the rolling one-minute window return `429 Too Many Requests`:

```json
{
  "type": "about:blank",
  "title": "Too Many Requests",
  "status": 429,
  "detail": "Link creation rate limit exceeded"
}
```

A rate-limited request does not validate or persist a link and does not consume a short-code generation attempt. The client address is `HttpServletRequest.getRemoteAddr()`; forwarding headers are ignored.

If the existing bounded short-code generation and collision retry limit is exhausted, the service returns `503 Service Unavailable`:

```json
{
  "type": "about:blank",
  "title": "Service Unavailable",
  "status": 503,
  "detail": "Unable to create a short link"
}
```

## `GET /{code}`

Resolves a short code, increments the click count, records a redirect event, and redirects to the stored destination.

### Success — `302 Found`

```bash
curl -i -H 'Referer: https://news.example/' \
  http://localhost:8080/Ab3xYz9
```

```http
HTTP/1.1 302 Found
Location: https://example.com/path?q=1
```

The response has no JSON body. `Location` contains the stored validated `originalUrl`; it is not constructed from the request host or configured public origin. Expiration is checked before click-count and redirect-event persistence.

### Missing or malformed code — `404 Not Found`

```bash
curl -i http://localhost:8080/Unknown
```

```json
{
  "type": "about:blank",
  "title": "Not Found",
  "status": 404,
  "detail": "Short link not found"
}
```

Codes must have seven URL-safe characters. Missing and malformed codes retain the existing not-found response.

### Expired link — `410 Gone`

At or after the persisted `expiresAt`, the service returns `410 Gone` and emits no `Location` header:

```bash
curl -i http://localhost:8080/Ab3xYz9
```

```http
HTTP/1.1 410 Gone
Content-Type: application/problem+json
```

```json
{
  "type": "about:blank",
  "title": "Gone",
  "status": 410,
  "detail": "Short link has expired"
}
```

Expired access does not increment clicks or create a redirect analytics event. It creates an `EXPIRED_LINK_ACCESS` audit event and an application log entry.

## `GET /api/links/{code}`

Returns link details without redirecting or incrementing the click count.

### Success — `200 OK`

```bash
curl -i http://localhost:8080/api/links/Ab3xYz9
```

```json
{
  "code": "Ab3xYz9",
  "shortUrl": "https://short.example.com/Ab3xYz9",
  "originalUrl": "https://example.com/path?q=1",
  "clickCount": 4,
  "expiresAt": "2026-10-31T12:00:00Z"
}
```

The `shortUrl` uses the configured public origin regardless of the request `Host` header. Legacy records may return `"expiresAt": null` and are non-expiring.

### Error — `404 Not Found`

```bash
curl -i http://localhost:8080/api/links/Unknown
```

```json
{
  "type": "about:blank",
  "title": "Not Found",
  "status": 404,
  "detail": "Short link not found"
}
```

## `GET /api/links/{code}/analytics`

Returns aggregate click analytics for a stored link.

### Success — `200 OK`

```bash
curl -i http://localhost:8080/api/links/Ab3xYz9/analytics
```

```json
{
  "totalClicks": 12,
  "clicksPerDay": [
    {"date": "2026-09-02", "count": 0},
    {"date": "2026-09-03", "count": 2},
    {"date": "2026-09-04", "count": 1}
  ],
  "topReferrers": [
    {"referrer": "https://example.org", "count": 5},
    {"referrer": "https://search.example", "count": 3}
  ]
}
```

The daily and referrer arrays contain the existing response objects. Days without events are represented according to the existing analytics behavior.

### Error — `404 Not Found`

```bash
curl -i http://localhost:8080/api/links/Unknown/analytics
```

```json
{
  "type": "about:blank",
  "title": "Not Found",
  "status": 404,
  "detail": "Short link not found"
}
```

Malformed codes use the same response. Unexpected analytics failures return the existing generic `500 Internal Server Error` ProblemDetail.

## Logging and audit behavior

Logs are written to standard console output through Spring Boot's existing logging framework. Rate-limit rejections include a bounded client identifier and outcome. Expired-link accesses include the short code and outcome. Full destinations, request bodies, credentials, API keys, passwords, authorization data, and unnecessary URL data are not logged.

Operational events are persisted in the existing `audit_events` table:

| Action | Trigger | Required context |
|---|---|---|
| `RATE_LIMIT_REJECTED` | Each HTTP 429 creation rejection | Client identifier, timestamp, `LIMIT_EXCEEDED` outcome |
| `EXPIRED_LINK_ACCESS` | Each HTTP 410 expired-link access | Short code, timestamp, `GONE` outcome |
| `CREATION_REJECTED` | Creation validation failure | Timestamp, client identifier, validation outcome |
| `LINK_CREATED` | Successful creation | Short code, timestamp, success outcome |
| `REDIRECTED` | Successful redirect | Short code, timestamp, redirect outcome |
| `UNKNOWN_CODE` | Missing link access | Safe code context, timestamp, not-found outcome |
| `ANALYTICS_REQUEST` | Existing analytics audit behavior | Existing operation outcome |

Rate-limit and expired-access audit writes use an independent failure path. If an audit write fails, the failure is logged and the intended `429` or `410` response is retained.
