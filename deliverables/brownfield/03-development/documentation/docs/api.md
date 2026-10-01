# HTTP API

The service exposes four public endpoints. JSON responses use the existing response shapes. Errors use RFC 7807 `ProblemDetail` JSON.

## `POST /api/links`

Creates an independent short-link record. Repeating the same destination is allowed and may produce a different code.

### Request

```http
POST /api/links
Content-Type: application/json
```

```json
{"url":"https://example.com/path?q=1"}
```

The `url` value is required, nonblank, absolute, and must use HTTP or HTTPS with a host. Its character length must not exceed 2048. A destination matching the configured `app.public-origin` and the shortener's redirect URL space is rejected to prevent redirect loops. No destination reachability check is performed.

### Success — `201 Created`

```bash
curl -i -X POST http://localhost:8080/api/links \
  -H 'Content-Type: application/json' \
  -d '{"url":"https://example.com/path?q=1"}'
```

```json
{
  "code":"Ab3xYz9",
  "shortUrl":"http://localhost:8080/Ab3xYz9",
  "originalUrl":"https://example.com/path?q=1",
  "clickCount":0
}
```

### Errors

Malformed, unsupported, blank, self-referential, and over-2048-character destinations return the existing invalid-URL response:

```http
HTTP/1.1 400 Bad Request
Content-Type: application/problem+json
```

```json
{
  "type":"about:blank",
  "title":"Bad Request",
  "status":400,
  "detail":"url must be an absolute HTTP or HTTPS URL"
}
```

For example, a self-link is rejected without persisting a link:

```bash
curl -i -X POST http://localhost:8080/api/links \
  -H 'Content-Type: application/json' \
  -d '{"url":"http://localhost:8080/Ab3xYz9"}'
```

A missing or blank field may use the existing binding response, such as `url must not be blank`; malformed JSON generally uses `request body is invalid`.

If all bounded short-code collision retries are exhausted, the service returns `503 Service Unavailable`:

```json
{
  "type":"about:blank",
  "title":"Service Unavailable",
  "status":503,
  "detail":"Unable to create a short link"
}
```

Unexpected failures return `500 Internal Server Error` with detail `An unexpected server error occurred`.

Rejected self-links and overlong destinations are logged and audited with action `CREATION_REJECTED` and outcomes `SELF_LINK` and `URL_TOO_LONG` respectively.

## `GET /api/links/{code}`

Returns link details without incrementing the click count. Codes must contain exactly seven URL-safe characters: `A-Z`, `a-z`, `0-9`, `_`, or `-`.

### Success — `200 OK`

```bash
curl -i http://localhost:8080/api/links/Ab3xYz9
```

```json
{
  "code":"Ab3xYz9",
  "shortUrl":"http://localhost:8080/Ab3xYz9",
  "originalUrl":"https://example.com/path?q=1",
  "clickCount":4
}
```

`shortUrl` is built from the configured `app.public-origin`; it is not derived from the incoming `Host` header.

### Error — `404 Not Found`

```bash
curl -i http://localhost:8080/api/links/Unknown
```

```json
{
  "type":"about:blank",
  "title":"Not Found",
  "status":404,
  "detail":"Short link not found"
}
```

The same response is used for malformed codes and codes that do not identify a stored link.

## `GET /{code}`

Resolves a code, atomically increments its existing click count, stores one redirect event, and redirects to the original URL. The optional HTTP `Referer` header is stored with the event when nonblank. Before persistence, the header is bounded to 2048 characters; absent or blank values are stored as null.

### Success — `302 Found`

```bash
curl -i -H 'Referer: https://news.example/' http://localhost:8080/Ab3xYz9
```

```http
HTTP/1.1 302 Found
Location: https://example.com/path?q=1
```

The response has no JSON body. Redirect-event persistence is part of the redirect operation; an event persistence failure does not produce a successful redirect.

### Error — `404 Not Found`

```bash
curl -i http://localhost:8080/Unknown
```

```json
{
  "type":"about:blank",
  "title":"Not Found",
  "status":404,
  "detail":"Short link not found"
}
```

Malformed and unknown codes use the same response. Unexpected redirect-processing or persistence failures return a generic `5xx` error, normally the service's `500 Internal Server Error` response.

## `GET /api/links/{code}/analytics`

Returns aggregate analytics for one stored link. The endpoint does not require authentication.

### Success — `200 OK`

```bash
curl -i http://localhost:8080/api/links/Ab3xYz9/analytics
```

```json
{
  "totalClicks":12,
  "clicksPerDay":[
    {"date":"2026-09-02","count":0},
    {"date":"2026-09-03","count":2},
    {"date":"2026-09-04","count":1}
  ],
  "topReferrers":[
    {"referrer":"https://example.org","count":5},
    {"referrer":"https://search.example","count":3}
  ]
}
```

The displayed daily list is abbreviated. The actual `clicksPerDay` array always contains exactly 30 entries, ordered from the oldest day through the current calendar day in `app.time-zone`. Days without events have count `0`. The database query includes only that 30-day range. `topReferrers` contains at most five nonblank referrers, ordered by descending count with deterministic ascending-referrer tie ordering. Redirects without a referrer still contribute to `totalClicks` and daily counts.

The response is computed with database-side count, grouping, ordering, and limiting queries; individual redirect events are not loaded into application memory.

### Error — `404 Not Found`

```bash
curl -i http://localhost:8080/api/links/Unknown/analytics
```

```json
{
  "type":"about:blank",
  "title":"Not Found",
  "status":404,
  "detail":"Short link not found"
}
```

Malformed codes use the same not-found response. The request is audited as `ANALYTICS_REQUEST` with outcome `NOT_FOUND`.

### Error — `500 Internal Server Error`

Database or unexpected analytics-processing failures return:

```json
{
  "type":"about:blank",
  "title":"Internal Server Error",
  "status":500,
  "detail":"An unexpected server error occurred"
}
```

The request is logged and audited as `ANALYTICS_REQUEST` with outcome `ERROR`.

## Persistence, logging, and audit behavior

Redirect events are stored in `redirect_events` with a link foreign key, precise timestamp, configured-timezone calendar day, and nullable referrer. Referrer values are truncated to 2048 characters before persistence so an oversized request header cannot make an otherwise valid redirect fail. Audit events are stored in `audit_events`.

| Audit action | Trigger | Outcome examples |
|---|---|---|
| `LINK_CREATED` | Link creation succeeds | `SUCCESS` |
| `CREATION_REJECTED` | Creation validation fails | `SELF_LINK`, `URL_TOO_LONG`, `INVALID_URL` |
| `REDIRECTED` | Redirect succeeds | `REDIRECTED` |
| `UNKNOWN_CODE` | Existing endpoint receives a missing valid-format code | `NOT_FOUND` |
| `ANALYTICS_REQUEST` | Analytics request begins and completes | `SUCCESS`, `NOT_FOUND`, `ERROR` |

Logs go to standard console output by default. Analytics requests include a bounded code, operation, timestamp supplied by the logging system, and outcome. Rejected self-links and overlong destinations include their safe rejection reason. Full URLs, request bodies, credentials, secrets, and raw referrers are not logged or audited.
