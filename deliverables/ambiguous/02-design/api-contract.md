# API contract

| Method | Path | Request | Success | Errors |
|---|---|---|---|---|
| POST | /api/links | {"url":"string, required, non-blank, absolute HTTP or HTTPS URL","expiresInDays":"integer, optional, 1 through 365 inclusive; omitted selects 90 days"} | 201 | 400 URL is blank, malformed, self-origin, expiration is outside 1-365, expiration is non-integer, or JSON is malformed; body is the existing RFC 7807 ProblemDetail format; 429 More than 30 admitted creation requests from the same direct remote address occur in the rolling one-minute window; 503 The existing bounded short-code generation and collision retry limit is exhausted |
| GET | /{code} | — | 302 | 404 The code is missing, malformed, or does not identify a link; existing not-found ProblemDetail is returned; 410 The link has a non-null expiresAt and the service clock is at or after it; standard expiration ProblemDetail is returned and no Location header is emitted |
| GET | /api/links/{code} | — | 200 | 404 The code is missing, malformed, or does not identify a link |
| GET | /api/links/{code}/analytics | — | 200 | 404 The code is missing, malformed, or does not identify a link |

## Full contract (JSON)

```json
[ {
  "method" : "POST",
  "path" : "/api/links",
  "request" : {
    "url" : "string, required, non-blank, absolute HTTP or HTTPS URL",
    "expiresInDays" : "integer, optional, 1 through 365 inclusive; omitted selects 90 days"
  },
  "responses" : [ {
    "status" : 201,
    "body" : {
      "code" : "string",
      "shortUrl" : "string based only on configured public origin",
      "originalUrl" : "string",
      "clickCount" : 0,
      "expiresAt" : "ISO-8601 timestamp or null for legacy records"
    }
  } ],
  "errors" : [ {
    "status" : 400,
    "when" : "URL is blank, malformed, self-origin, expiration is outside 1-365, expiration is non-integer, or JSON is malformed; body is the existing RFC 7807 ProblemDetail format"
  }, {
    "status" : 429,
    "when" : "More than 30 admitted creation requests from the same direct remote address occur in the rolling one-minute window"
  }, {
    "status" : 503,
    "when" : "The existing bounded short-code generation and collision retry limit is exhausted"
  } ]
}, {
  "method" : "GET",
  "path" : "/{code}",
  "request" : null,
  "responses" : [ {
    "status" : 302,
    "body" : null,
    "headers" : {
      "Location" : "stored validated originalUrl"
    }
  } ],
  "errors" : [ {
    "status" : 404,
    "when" : "The code is missing, malformed, or does not identify a link; existing not-found ProblemDetail is returned"
  }, {
    "status" : 410,
    "when" : "The link has a non-null expiresAt and the service clock is at or after it; standard expiration ProblemDetail is returned and no Location header is emitted"
  } ]
}, {
  "method" : "GET",
  "path" : "/api/links/{code}",
  "request" : null,
  "responses" : [ {
    "status" : 200,
    "body" : {
      "code" : "string",
      "shortUrl" : "string based only on configured public origin",
      "originalUrl" : "string",
      "clickCount" : "number",
      "expiresAt" : "ISO-8601 timestamp or null"
    }
  } ],
  "errors" : [ {
    "status" : 404,
    "when" : "The code is missing, malformed, or does not identify a link"
  } ]
}, {
  "method" : "GET",
  "path" : "/api/links/{code}/analytics",
  "request" : null,
  "responses" : [ {
    "status" : 200,
    "body" : {
      "totalClicks" : "number",
      "clicksPerDay" : "array of the existing daily click response objects",
      "topReferrers" : "array of the existing referrer response objects"
    }
  } ],
  "errors" : [ {
    "status" : 404,
    "when" : "The code is missing, malformed, or does not identify a link"
  } ]
} ]
```

