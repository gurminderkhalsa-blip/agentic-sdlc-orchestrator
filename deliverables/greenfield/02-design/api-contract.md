# API contract

| Method | Path | Request | Success | Errors |
|---|---|---|---|---|
| POST | /api/links | {"url":"string, required, absolute HTTP or HTTPS URL"} | 201 | 400 The body is missing or malformed, url is missing or blank, or url is not an absolute HTTP or HTTPS URL. Returns RFC 7807 ProblemDetail.; 503 A bounded number of unique-code insertion attempts is exhausted. Returns generic RFC 7807 ProblemDetail.; 500 Unexpected server failure. Returns generic RFC 7807 ProblemDetail. |
| GET | /{code} | — | 302 | 404 The seven-character code does not exist. Returns RFC 7807 ProblemDetail and no Location header.; 500 Unexpected server failure. Returns generic RFC 7807 ProblemDetail. |
| GET | /api/links/{code} | — | 200 | 404 The code does not exist. Returns RFC 7807 ProblemDetail.; 500 Unexpected server failure. Returns generic RFC 7807 ProblemDetail. |

## Full contract (JSON)

```json
[ {
  "method" : "POST",
  "path" : "/api/links",
  "request" : {
    "url" : "string, required, absolute HTTP or HTTPS URL"
  },
  "responses" : [ {
    "status" : 201,
    "body" : {
      "code" : "Ab3xYz9",
      "shortUrl" : "http://localhost:8080/Ab3xYz9",
      "originalUrl" : "https://example.com/path?q=1",
      "clickCount" : 0
    }
  } ],
  "errors" : [ {
    "status" : 400,
    "when" : "The body is missing or malformed, url is missing or blank, or url is not an absolute HTTP or HTTPS URL. Returns RFC 7807 ProblemDetail."
  }, {
    "status" : 503,
    "when" : "A bounded number of unique-code insertion attempts is exhausted. Returns generic RFC 7807 ProblemDetail."
  }, {
    "status" : 500,
    "when" : "Unexpected server failure. Returns generic RFC 7807 ProblemDetail."
  } ]
}, {
  "method" : "GET",
  "path" : "/{code}",
  "request" : null,
  "responses" : [ {
    "status" : 302,
    "body" : null,
    "headers" : {
      "Location" : "stored original URL"
    }
  } ],
  "errors" : [ {
    "status" : 404,
    "when" : "The seven-character code does not exist. Returns RFC 7807 ProblemDetail and no Location header."
  }, {
    "status" : 500,
    "when" : "Unexpected server failure. Returns generic RFC 7807 ProblemDetail."
  } ]
}, {
  "method" : "GET",
  "path" : "/api/links/{code}",
  "request" : null,
  "responses" : [ {
    "status" : 200,
    "body" : {
      "code" : "Ab3xYz9",
      "shortUrl" : "http://localhost:8080/Ab3xYz9",
      "originalUrl" : "https://example.com/path?q=1",
      "clickCount" : 4
    }
  } ],
  "errors" : [ {
    "status" : 404,
    "when" : "The code does not exist. Returns RFC 7807 ProblemDetail."
  }, {
    "status" : 500,
    "when" : "Unexpected server failure. Returns generic RFC 7807 ProblemDetail."
  } ]
} ]
```

