# Coverage gaps: where the 100% target was not achieved

**The 100% target was not fully achieved.** 24 line(s) and 36 branch(es) are not covered. They are listed below by file and line number, followed by the reasons the QA agent gave.

## Uncovered code (from the JaCoCo report)

| Source file | Uncovered lines | Lines with partly covered branches |
|---|---|---|
| com.example.shortener.UrlShortenerApplication.java | 10-11 | — |
| com.example.shortener.controller.GlobalExceptionHandler.java | — | [34, 52, 92] |
| com.example.shortener.api.LinkResponse.java | 8-9 | — |
| com.example.shortener.api.CreateLinkRequest.java | 17-18 | [27, 30] |
| com.example.shortener.api.ErrorResponse.java | 3 | — |
| com.example.shortener.domain.RedirectEvent.java | 49 | — |
| com.example.shortener.domain.AuditEvent.java | 44 | — |
| com.example.shortener.service.LinkService.java | 65-67, 79, 120 | [57, 78, 119, 123, 128, 160] |
| com.example.shortener.service.ShortUrlBuilder.java | — | [25, 44, 53] |
| com.example.shortener.service.AuditService.java | 38-40 | [22, 46] |
| com.example.shortener.service.LinkAnalyticsService.java | — | [42, 48, 91] |
| com.example.shortener.service.UrlValidator.java | 30, 33, 40, 43, 46, 78 | [42, 53, 63, 69, 72, 75, 77, 80, 81] |
| com.example.shortener.service.CreationRateLimiter.java | 33 | [32, 44] |

## Reasons given by the QA agent

_None._

