# Error handling, logging and auditing

Extracted from the released source code. The loggingAndAuditing gate required every controller and service to log through SLF4J and an audit entity to exist before implementation was accepted.

## Logging (SLF4J calls per class)

| Class | info | warn | error | debug |
|---|---|---|---|---|
| GlobalExceptionHandler.java | 0 | 2 | 2 | 0 |
| LinkController.java | 2 | 0 | 0 | 0 |
| RedirectController.java | 1 | 0 | 0 | 0 |
| AuditService.java | 0 | 0 | 1 | 0 |
| LinkService.java | 2 | 3 | 1 | 0 |
| UrlValidator.java | 0 | 1 | 0 | 0 |

## Audit trail entities

- AuditEvent.java

## Error handling (exception handlers)

| Class | Handles | HTTP status |
|---|---|---|
| GlobalExceptionHandler.java | InvalidUrlException | BAD_REQUEST |
| GlobalExceptionHandler.java | {MethodArgumentNotValidException, ConstraintViolationException,<br>            HttpMessageNotReadableException} | BAD_REQUEST |
| GlobalExceptionHandler.java | CodeGenerationException | SERVICE_UNAVAILABLE |
| GlobalExceptionHandler.java | LinkNotFoundException | NOT_FOUND |
| GlobalExceptionHandler.java | Exception | INTERNAL_SERVER_ERROR |

