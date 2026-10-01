# HTTP API

The service exposes three public endpoints. Successful responses use JSON except for redirects. Errors use RFC 7807 `ProblemDetail` JSON.

## `POST /api/links`

Creates an independent link record. Submitting the same URL again is allowed and may produce a different code.

### Request

```http
POST /api/links
Content-Type: application/json
```

```json
{"url":"https://example.com/path?q=1"}
```

The value must be an absolute URL with an `http` or `https` scheme, a non-empty host, and a maximum length of 2048 characters. The submitted URL is retained after JSON parsing; no reachability check is performed.

### Success: `201 Created`

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

`400 Bad Request` is returned for a missing or malformed body, a missing or blank `url`, an invalid URL, an unsupported scheme, or a URL longer than 2048 characters.

```bash
curl -i -X POST http://localhost:8080/api/links \
  -H 'Content-Type: application/json' \
  -d '{"url":"not a URL"}'
```

```json
{
  "type":"about:blank",
  "title":"Bad Request",
  "status":400,
  "detail":"url must be an absolute HTTP or HTTPS URL",
  "instance":"/api/links"
}
```

Malformed JSON generally returns `detail: "request body is invalid"`; a blank field generally returns `detail: "url must not be blank"`. Rejected creations are audited with action `CREATION_REJECTED` and a null code.

`503 Service Unavailable` is returned if all bounded unique-code insertion attempts are exhausted:

```json
{
  "type":"about:blank",
  "title":"Service Unavailable",
  "status":503,
  "detail":"Unable to create a short link"
}
```

Unexpected failures, including audit persistence failures, return `500 Internal Server Error` with detail `An unexpected server error occurred`. Responses do not expose stack traces or persistence details.

## `GET /{code}`

Resolves a code, atomically increments its click count, and redirects to the stored original URL. The path variable must contain exactly seven URL-safe characters: `A-Z`, `a-z`, `0-9`, `_`, or `-`. Generated codes are alphanumeric.

### Success: `302 Found`

```bash
curl -i http://localhost:8080/Ab3xYz9
```

```http
HTTP/1.1 302 Found
Location: https://example.com/path?q=1
```

The response has no JSON body. Each successful resolution writes a `REDIRECTED` audit event in the same transaction as the click-count update.

### Errors

An unknown or structurally invalid code returns `404 Not Found` without a `Location` header:

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

Invalid-format codes are rejected before lookup, logging, or auditing. A valid-format code that is not stored creates an `UNKNOWN_CODE` audit event in an independent transaction. Unexpected failures return the generic `500` ProblemDetail.

## `GET /api/links/{code}`

Returns details without incrementing the click count. The path variable must contain exactly seven URL-safe characters.

### Success: `200 OK`

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

The `shortUrl` base comes from `shortener.public-base-url` when configured; otherwise it is derived from the incoming request.

### Errors

An unknown or malformed code returns `404 Not Found`:

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

Valid-format missing codes create an `UNKNOWN_CODE` audit event in an independent transaction. Invalid-format codes are rejected before logging or auditing. Unexpected failures return the generic `500` ProblemDetail.

## Audit and operational behavior

The `audit_events` table contains `event_time`, `action`, nullable `code`, `client_ip`, and `outcome` columns:

| Action | Trigger | Outcome examples |
|---|---|---|
| `LINK_CREATED` | Link insert succeeds | `SUCCESS` |
| `CREATION_REJECTED` | Creation validation or binding fails | `INVALID_URL:<reason>`, `INVALID_REQUEST:<reason>` |
| `REDIRECTED` | Known redirect resolves | `REDIRECTED` |
| `UNKNOWN_CODE` | A valid-format code is absent | `NOT_FOUND` |

`LINK_CREATED` and `REDIRECTED` audit writes join their corresponding state-change transactions. `CREATION_REJECTED` and `UNKNOWN_CODE` writes use independent transactions so they persist after `400` and `404` responses. Audit failures are logged and propagated rather than silently swallowed; a successful state-changing response is not returned when its required audit write fails.
