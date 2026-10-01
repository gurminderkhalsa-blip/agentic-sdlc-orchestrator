# URL Shortener

A Spring Boot URL shortener backed by Spring Data JPA and the configured H2 database. Clients can create short links, follow them through redirects, retrieve link details, and inspect aggregate click counts.

## Endpoints

- `POST /api/links` creates a short link.
- `GET /{code}` redirects to the original URL and increments the click count.
- `GET /api/links/{code}` returns link details and the current click count.

Generated codes are exactly seven alphanumeric characters, use `SecureRandom`, and are protected by a unique database constraint. Repeated submissions of the same URL create independent links.

## Requirements

- Java 21
- The Gradle wrapper included in the repository

## Build

```bash
./gradlew build
```

## Run

```bash
./gradlew bootRun
```

The service listens on port `8080` by default. H2 provides runtime persistence; data is not required to survive application restarts.

## Test

```bash
./gradlew test
```

Gradle writes test reports under `build/reports/tests/`.

## Configuration

The optional `shortener.public-base-url` property controls the base URL returned in `shortUrl` responses:

```yaml
shortener:
  public-base-url: https://short.example.com
```

When absent or blank, the service derives the base URL from the incoming request scheme, host, port, and context path. Forwarded headers are not trusted.

The H2, JPA, server, and logging settings are in `src/main/resources/application.yml` and may be overridden with standard Spring configuration mechanisms. Do not put credentials or other secrets in source control.

## API examples

### Create a link

```bash
curl -i -X POST http://localhost:8080/api/links \
  -H 'Content-Type: application/json' \
  -d '{"url":"https://example.com/path?q=1"}'
```

A successful request returns `201 Created`:

```json
{
  "code": "Ab3xYz9",
  "shortUrl": "http://localhost:8080/Ab3xYz9",
  "originalUrl": "https://example.com/path?q=1",
  "clickCount": 0
}
```

Only absolute URLs with an `http` or `https` scheme, a host, and a maximum length of 2048 characters are accepted. Invalid, missing, blank, malformed, unsupported-scheme, or overlong URLs return `400 Bad Request` as RFC 7807 `ProblemDetail` JSON:

```bash
curl -i -X POST http://localhost:8080/api/links \
  -H 'Content-Type: application/json' \
  -d '{"url":"ftp://example.com/file"}'
```

```json
{
  "type": "about:blank",
  "title": "Bad Request",
  "status": 400,
  "detail": "url must be an absolute HTTP or HTTPS URL",
  "instance": "/api/links"
}
```

### Follow a short link

Use `-i` without `-L` to inspect the response rather than following it:

```bash
curl -i http://localhost:8080/Ab3xYz9
```

A known code returns `302 Found` with the stored URL in `Location` and atomically increments the click count:

```http
HTTP/1.1 302 Found
Location: https://example.com/path?q=1
```

An unknown or malformed code returns `404 Not Found` without a `Location` header:

```bash
curl -i http://localhost:8080/Unknown
```

The code path must contain exactly seven URL-safe characters (`A-Z`, `a-z`, `0-9`, `_`, or `-`). Invalid-format codes are rejected before lookup or logging and use the same fixed not-found response as unknown codes.

### Get link details

```bash
curl -i http://localhost:8080/api/links/Ab3xYz9
```

A successful request returns `200 OK`:

```json
{
  "code": "Ab3xYz9",
  "shortUrl": "http://localhost:8080/Ab3xYz9",
  "originalUrl": "https://example.com/path?q=1",
  "clickCount": 4
}
```

An unknown or malformed code returns `404 Not Found`:

```bash
curl -i http://localhost:8080/api/links/Unknown
```

Code-generation retry exhaustion returns `503`; other unexpected failures, including an audit persistence failure, return `500`. These responses use generic RFC 7807 problem responses without stack traces, database details, credentials, or secrets.

## Logging

The service uses SLF4J through Spring Boot. By default, logs go to the application process's standard console output. This service does not define an application-specific file appender; deployments can redirect or collect standard output through their process manager or container platform.

The service logs:

- successful `LINK_CREATED` and `REDIRECTED` operations at `INFO`, including the short code;
- `CREATION_REJECTED`, `UNKNOWN_CODE`, and code-collision retries at `WARN`;
- audit persistence failures, retry exhaustion, unexpected request failures, and infrastructure failures at `ERROR`.

The full original URL and request body are not logged. Client IP addresses are persisted for audit purposes but are not included in normal success logs. Unexpected HTTP errors are generic.

## Audit trail

Audit entities are stored through Spring Data JPA in the H2 table `audit_events`. Fields are:

- `event_time`: service-generated timestamp;
- `action`: `LINK_CREATED`, `CREATION_REJECTED`, `REDIRECTED`, or `UNKNOWN_CODE`;
- `code`: generated or requested code when available, or null for rejected creation;
- `client_ip`: servlet request remote address, or `unknown` when unavailable;
- `outcome`: such as `SUCCESS`, `INVALID_URL:<reason>`, `INVALID_REQUEST:<reason>`, `REDIRECTED`, or `NOT_FOUND`.

Successful state changes are audited transactionally: `LINK_CREATED` joins the link insert transaction, and `REDIRECTED` joins the atomic click-count update transaction. If either audit write fails, the operation fails rather than silently succeeding.

Rejected creations and unknown-code requests are audited in independent `REQUIRES_NEW` transactions, so their audit rows remain persisted even though the HTTP request returns `400` or `404`. Invalid-format codes are validated before audit logging; valid-format missing codes are recorded with `UNKNOWN_CODE`, and rejected creations use a null code.

Links are stored in the `links` table. Redirect click counts use an atomic database update so concurrent successful redirects do not lose increments. H2 data is runtime state and is not required to survive restarts.
