# API contract

| Method | Path | Request | Success | Errors |
|---|---|---|---|---|
| POST | /api/links | {"url":"string, required, nonblank, absolute HTTP or HTTPS URL, maximum 2048 characters"} | 201, 400, 503 | 400 Malformed, unsupported, blank, self-referential, or over-2048-character destination; uses the existing invalid-URL response.; 503 The existing short-code collision retry limit is exhausted. |
| GET | /api/links/{code} | — | 200, 404 | 404 The code is invalid or no link exists. |
| GET | /{code} | — | 302, 404 | 404 The code is invalid or no link exists.; 5xx Redirect event persistence or another unexpected redirect-processing failure occurs; the transaction does not return a successful redirect. |
| GET | /api/links/{code}/analytics | — | 200, 404, 500 | 404 The supplied code does not identify an existing link; the request is audited as an analytics not-found outcome.; 500 A database or unexpected analytics-processing error occurs; the request is logged and audited as an error. |

## Full contract (JSON)

```json
[ {
  "method" : "POST",
  "path" : "/api/links",
  "request" : {
    "url" : "string, required, nonblank, absolute HTTP or HTTPS URL, maximum 2048 characters"
  },
  "responses" : [ {
    "status" : 201,
    "body" : {
      "code" : "string",
      "shortUrl" : "string",
      "originalUrl" : "string",
      "clickCount" : 0
    }
  }, {
    "status" : 400,
    "body" : {
      "type" : "about:blank",
      "title" : "Bad Request",
      "status" : 400,
      "detail" : "url must be an absolute HTTP or HTTPS URL"
    }
  }, {
    "status" : 503,
    "body" : {
      "type" : "about:blank",
      "title" : "Service Unavailable",
      "status" : 503,
      "detail" : "Unable to create a short link"
    }
  } ],
  "errors" : [ {
    "status" : 400,
    "when" : "Malformed, unsupported, blank, self-referential, or over-2048-character destination; uses the existing invalid-URL response."
  }, {
    "status" : 503,
    "when" : "The existing short-code collision retry limit is exhausted."
  } ]
}, {
  "method" : "GET",
  "path" : "/api/links/{code}",
  "request" : null,
  "responses" : [ {
    "status" : 200,
    "body" : {
      "code" : "string",
      "shortUrl" : "string",
      "originalUrl" : "string",
      "clickCount" : 0
    }
  }, {
    "status" : 404,
    "body" : {
      "type" : "about:blank",
      "title" : "Not Found",
      "status" : 404,
      "detail" : "Short link not found"
    }
  } ],
  "errors" : [ {
    "status" : 404,
    "when" : "The code is invalid or no link exists."
  } ]
}, {
  "method" : "GET",
  "path" : "/{code}",
  "request" : null,
  "responses" : [ {
    "status" : 302,
    "body" : null
  }, {
    "status" : 404,
    "body" : {
      "type" : "about:blank",
      "title" : "Not Found",
      "status" : 404,
      "detail" : "Short link not found"
    }
  } ],
  "errors" : [ {
    "status" : 404,
    "when" : "The code is invalid or no link exists."
  }, {
    "status" : "5xx",
    "when" : "Redirect event persistence or another unexpected redirect-processing failure occurs; the transaction does not return a successful redirect."
  } ]
}, {
  "method" : "GET",
  "path" : "/api/links/{code}/analytics",
  "request" : null,
  "responses" : [ {
    "status" : 200,
    "body" : {
      "totalClicks" : 12,
      "clicksPerDay" : [ {
        "date" : "2026-09-02",
        "count" : 0
      }, {
        "date" : "2026-09-03",
        "count" : 2
      } ],
      "topReferrers" : [ {
        "referrer" : "https://example.org",
        "count" : 5
      } ]
    }
  }, {
    "status" : 404,
    "body" : {
      "type" : "about:blank",
      "title" : "Not Found",
      "status" : 404,
      "detail" : "Short link not found"
    }
  }, {
    "status" : 500,
    "body" : {
      "type" : "about:blank",
      "title" : "Internal Server Error",
      "status" : 500,
      "detail" : "An unexpected server error occurred"
    }
  } ],
  "errors" : [ {
    "status" : 404,
    "when" : "The supplied code does not identify an existing link; the request is audited as an analytics not-found outcome."
  }, {
    "status" : 500,
    "when" : "A database or unexpected analytics-processing error occurs; the request is logged and audited as an error."
  } ]
} ]
```

