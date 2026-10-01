# Implementation change set

Make audit transaction propagation explicit for successful and failed operations, persist rejection and unknown-code audits independently, sanitize invalid-code logging, and use a fixed not-found response.

| Action | File | Lines |
|---|---|---|
| create | src/main/java/com/example/shortener/api/CreateLinkRequest.java | 6 |
| create | src/main/java/com/example/shortener/api/ErrorResponse.java | 4 |
| create | src/main/java/com/example/shortener/api/LinkResponse.java | 4 |
| create | src/main/java/com/example/shortener/controller/GlobalExceptionHandler.java | 74 |
| create | src/main/java/com/example/shortener/controller/LinkController.java | 40 |
| create | src/main/java/com/example/shortener/controller/RedirectController.java | 30 |
| create | src/main/java/com/example/shortener/domain/AuditEvent.java | 50 |
| create | src/main/java/com/example/shortener/domain/Link.java | 59 |
| create | src/main/java/com/example/shortener/exception/InvalidUrlException.java | 7 |
| create | src/main/java/com/example/shortener/exception/LinkNotFoundException.java | 11 |
| create | src/main/java/com/example/shortener/repository/AuditEventRepository.java | 7 |
| create | src/main/java/com/example/shortener/repository/LinkRepository.java | 18 |
| create | src/main/java/com/example/shortener/service/AuditService.java | 54 |
| create | src/main/java/com/example/shortener/service/LinkService.java | 122 |
| create | src/main/java/com/example/shortener/service/ShortCodeGenerator.java | 20 |
| create | src/main/java/com/example/shortener/service/ShortUrlBuilder.java | 28 |
| create | src/main/java/com/example/shortener/service/UrlValidator.java | 40 |
| create | src/main/java/com/example/shortener/exception/CodeGenerationException.java | 7 |

## Diff

```diff
--- a/src/main/java/com/example/shortener/api/CreateLinkRequest.java
+++ b/src/main/java/com/example/shortener/api/CreateLinkRequest.java
@@ -0,0 +1,6 @@
+package com.example.shortener.api;
+
+import jakarta.validation.constraints.NotBlank;
+
+public record CreateLinkRequest(@NotBlank(message = "url must not be blank") String url) {
+}
--- a/src/main/java/com/example/shortener/api/ErrorResponse.java
+++ b/src/main/java/com/example/shortener/api/ErrorResponse.java
@@ -0,0 +1,4 @@
+package com.example.shortener.api;
+
+public record ErrorResponse(String title, int status, String detail) {
+}
--- a/src/main/java/com/example/shortener/api/LinkResponse.java
+++ b/src/main/java/com/example/shortener/api/LinkResponse.java
@@ -0,0 +1,4 @@
+package com.example.shortener.api;
+
+public record LinkResponse(String code, String shortUrl, String originalUrl, long clickCount) {
+}
--- a/src/main/java/com/example/shortener/controller/GlobalExceptionHandler.java
+++ b/src/main/java/com/example/shortener/controller/GlobalExceptionHandler.java
@@ -0,0 +1,74 @@
+package com.example.shortener.controller;
+
+import com.example.shortener.exception.CodeGenerationException;
+import com.example.shortener.exception.InvalidUrlException;
+import com.example.shortener.exception.LinkNotFoundException;
+import com.example.shortener.service.AuditService;
+import jakarta.servlet.http.HttpServletRequest;
+import jakarta.validation.ConstraintViolationException;
+import org.slf4j.Logger;
+import org.slf4j.LoggerFactory;
+import org.springframework.http.HttpStatus;
+import org.springframework.http.ProblemDetail;
+import org.springframework.http.ResponseEntity;
+import org.springframework.http.converter.HttpMessageNotReadableException;
+import org.springframework.web.bind.MethodArgumentNotValidException;
+import org.springframework.web.bind.annotation.ExceptionHandler;
+import org.springframework.web.bind.annotation.RestControllerAdvice;
+
+@RestControllerAdvice
+public class GlobalExceptionHandler {
+    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
+    private final AuditService audit;
+
+    public GlobalExceptionHandler(AuditService audit) {
+        this.audit = audit;
+    }
+
+    @ExceptionHandler(InvalidUrlException.class)
+    public ResponseEntity<ProblemDetail> invalid(InvalidUrlException ex, HttpServletRequest request) {
+        reject(request, "INVALID_URL:" + ex.getMessage());
+        log.warn("CREATION_REJECTED outcome=INVALID_URL");
+        return problem(HttpStatus.BAD_REQUEST, ex.getMessage());
+    }
+
+    @ExceptionHandler({MethodArgumentNotValidException.class, ConstraintViolationException.class,
+            HttpMessageNotReadableException.class})
+    public ResponseEntity<ProblemDetail> badRequest(Exception ex, HttpServletRequest request) {
+        String detail = ex instanceof MethodArgumentNotValidException
+                ? "url must not be blank" : "request body is invalid";
+        reject(request, "INVALID_REQUEST:" + detail);
+        log.warn("CREATION_REJECTED outcome=INVALID_REQUEST");
+        return problem(HttpStatus.BAD_REQUEST, detail);
+    }
+
+    @ExceptionHandler(CodeGenerationException.class)
+    public ResponseEntity<ProblemDetail> codeGeneration(CodeGenerationException ex) {
+        log.error("Short-code generation failed");
+        return problem(HttpStatus.SERVICE_UNAVAILABLE, "Unable to create a short link");
+    }
+
+    @ExceptionHandler(LinkNotFoundException.class)
+    public ResponseEntity<ProblemDetail> notFound(LinkNotFoundException ex) {
+        return problem(HttpStatus.NOT_FOUND, ex.getMessage());
+    }
+
+    @ExceptionHandler(Exception.class)
+    public ResponseEntity<ProblemDetail> unexpected(Exception ex) {
+        log.error("Unexpected request-processing error type={}", ex.getClass().getSimpleName());
+        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected server error occurred");
+    }
+
+    private void reject(HttpServletRequest request, String outcome) {
+        if (request.getRequestURI().equals("/api/links")
+                || request.getRequestURI().startsWith("/api/links/")) {
+            audit.recordFailure("CREATION_REJECTED", null, request.getRemoteAddr(), outcome);
+        }
+    }
+
+    private ResponseEntity<ProblemDetail> problem(HttpStatus status, String detail) {
+        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
+        problem.setTitle(status.getReasonPhrase());
+        return ResponseEntity.status(status).body(problem);
+    }
+}
--- a/src/main/java/com/example/shortener/controller/LinkController.java
+++ b/src/main/java/com/example/shortener/controller/LinkController.java
@@ -0,0 +1,40 @@
+package com.example.shortener.controller;
+
+import com.example.shortener.api.CreateLinkRequest;
+import com.example.shortener.api.LinkResponse;
+import com.example.shortener.service.LinkService;
+import jakarta.servlet.http.HttpServletRequest;
+import jakarta.validation.Valid;
+import org.slf4j.Logger;
+import org.slf4j.LoggerFactory;
+import org.springframework.http.ResponseEntity;
+import org.springframework.web.bind.annotation.GetMapping;
+import org.springframework.web.bind.annotation.PathVariable;
+import org.springframework.web.bind.annotation.PostMapping;
+import org.springframework.web.bind.annotation.RequestBody;
+import org.springframework.web.bind.annotation.RequestMapping;
+import org.springframework.web.bind.annotation.RestController;
+
+@RestController
+@RequestMapping("/api/links")
+public class LinkController {
+    private static final Logger log = LoggerFactory.getLogger(LinkController.class);
+    private final LinkService service;
+
+    public LinkController(LinkService service) {
+        this.service = service;
+    }
+
+    @PostMapping
+    public ResponseEntity<LinkResponse> create(@Valid @RequestBody CreateLinkRequest request,
+                                                HttpServletRequest servletRequest) {
+        log.info("Processing link creation request");
+        return ResponseEntity.status(201).body(service.create(request.url(), servletRequest));
+    }
+
+    @GetMapping("/{code}")
+    public LinkResponse details(@PathVariable String code, HttpServletRequest request) {
+        log.info("Processing link details request");
+        return service.details(code, request);
+    }
+}
--- a/src/main/java/com/example/shortener/controller/RedirectController.java
+++ b/src/main/java/com/example/shortener/controller/RedirectController.java
@@ -0,0 +1,30 @@
+package com.example.shortener.controller;
+
+import com.example.shortener.service.LinkService;
+import jakarta.servlet.http.HttpServletRequest;
+import org.slf4j.Logger;
+import org.slf4j.LoggerFactory;
+import org.springframework.http.HttpHeaders;
+import org.springframework.http.HttpStatus;
+import org.springframework.http.ResponseEntity;
+import org.springframework.web.bind.annotation.GetMapping;
+import org.springframework.web.bind.annotation.PathVariable;
+import org.springframework.web.bind.annotation.RestController;
+
+@RestController
+public class RedirectController {
+    private static final Logger log = LoggerFactory.getLogger(RedirectController.class);
+    private final LinkService service;
+
+    public RedirectController(LinkService service) {
+        this.service = service;
+    }
+
+    @GetMapping("/{code}")
+    public ResponseEntity<Void> redirect(@PathVariable String code, HttpServletRequest request) {
+        log.info("Processing redirect request");
+        HttpHeaders headers = new HttpHeaders();
+        headers.setLocation(java.net.URI.create(service.resolve(code, request)));
+        return new ResponseEntity<>(headers, HttpStatus.FOUND);
+    }
+}
--- a/src/main/java/com/example/shortener/domain/AuditEvent.java
+++ b/src/main/java/com/example/shortener/domain/AuditEvent.java
@@ -0,0 +1,50 @@
+package com.example.shortener.domain;
+
+import jakarta.persistence.Column;
+import jakarta.persistence.Entity;
+import jakarta.persistence.GeneratedValue;
+import jakarta.persistence.GenerationType;
+import jakarta.persistence.Id;
+import jakarta.persistence.Table;
+import java.time.Instant;
+
+@Entity
+@Table(name = "audit_events")
+public class AuditEvent {
+    @Id
+    @GeneratedValue(strategy = GenerationType.IDENTITY)
+    private Long id;
+
+    @Column(name = "event_time", nullable = false)
+    private Instant eventTime;
+
+    @Column(nullable = false, length = 32)
+    private String action;
+
+    @Column(length = 7)
+    private String code;
+
+    @Column(name = "client_ip", nullable = false, length = 128)
+    private String clientIp;
+
+    @Column(nullable = false, length = 256)
+    private String outcome;
+
+    protected AuditEvent() {
+    }
+
+    public AuditEvent(Instant eventTime, String action, String code, String clientIp, String outcome) {
+        this.eventTime = eventTime;
+        this.action = action;
+        this.code = code;
+        this.clientIp = clientIp;
+        this.outcome = outcome;
+    }
+
+    public Long getId() { return id; }
+    public Instant getEventTime() { return eventTime; }
+    public String getAction() { return action; }
+    public String getCode() { return code; }
+    public String getClientIp() { return clientIp; }
+    public String getOutcome() { return outcome; }
+}
--- a/src/main/java/com/example/shortener/domain/Link.java
+++ b/src/main/java/com/example/shortener/domain/Link.java
@@ -0,0 +1,59 @@
+package com.example.shortener.domain;
+
+import jakarta.persistence.Column;
+import jakarta.persistence.Entity;
+import jakarta.persistence.GeneratedValue;
+import jakarta.persistence.GenerationType;
+import jakarta.persistence.Id;
+import jakarta.persistence.Table;
+import java.time.Instant;
+
+@Entity
+@Table(name = "links")
+public class Link {
+    @Id
+    @GeneratedValue(strategy = GenerationType.IDENTITY)
+    private Long id;
+
+    @Column(nullable = false, unique = true, length = 7)
+    private String code;
+
+    @Column(name = "original_url", nullable = false, length = 2048)
+    private String originalUrl;
+
+    @Column(name = "click_count", nullable = false)
+    private long clickCount;
+
+    @Column(name = "created_at", nullable = false)
+    private Instant createdAt;
+
+    protected Link() {
+    }
+
+    public Link(String code, String originalUrl, Instant createdAt) {
+        this.code = code;
+        this.originalUrl = originalUrl;
+        this.createdAt = createdAt;
+        this.clickCount = 0;
+    }
+
+    public Long getId() {
+        return id;
+    }
+
+    public String getCode() {
+        return code;
+    }
+
+    public String getOriginalUrl() {
+        return originalUrl;
+    }
+
+    public long getClickCount() {
+        return clickCount;
+    }
+
+    public Instant getCreatedAt() {
+        return createdAt;
+    }
+}
--- a/src/main/java/com/example/shortener/exception/InvalidUrlException.java
+++ b/src/main/java/com/example/shortener/exception/InvalidUrlException.java
@@ -0,0 +1,7 @@
+package com.example.shortener.exception;
+
+public class InvalidUrlException extends RuntimeException {
+    public InvalidUrlException(String message) {
+        super(message);
+    }
+}
--- a/src/main/java/com/example/shortener/exception/LinkNotFoundException.java
+++ b/src/main/java/com/example/shortener/exception/LinkNotFoundException.java
@@ -0,0 +1,11 @@
+package com.example.shortener.exception;
+
+public class LinkNotFoundException extends RuntimeException {
+    public LinkNotFoundException() {
+        super("Short link not found");
+    }
+
+    public LinkNotFoundException(String ignoredCode) {
+        this();
+    }
+}
--- a/src/main/java/com/example/shortener/repository/AuditEventRepository.java
+++ b/src/main/java/com/example/shortener/repository/AuditEventRepository.java
@@ -0,0 +1,7 @@
+package com.example.shortener.repository;
+
+import com.example.shortener.domain.AuditEvent;
+import org.springframework.data.jpa.repository.JpaRepository;
+
+public interface AuditEventRepository extends JpaRepository<AuditEvent, Long> {
+}
--- a/src/main/java/com/example/shortener/repository/LinkRepository.java
+++ b/src/main/java/com/example/shortener/repository/LinkRepository.java
@@ -0,0 +1,18 @@
+package com.example.shortener.repository;
+
+import com.example.shortener.domain.Link;
+import org.springframework.data.jpa.repository.JpaRepository;
+import org.springframework.data.jpa.repository.Modifying;
+import org.springframework.data.jpa.repository.Query;
+import org.springframework.data.repository.query.Param;
+
+import java.util.Optional;
+
+public interface LinkRepository extends JpaRepository<Link, Long> {
+    Optional<Link> findByCode(String code);
+    boolean existsByCode(String code);
+
+    @Modifying
+    @Query("update Link l set l.clickCount = l.clickCount + 1 where l.code = :code")
+    int incrementClickCount(@Param("code") String code);
+}
--- a/src/main/java/com/example/shortener/service/AuditService.java
+++ b/src/main/java/com/example/shortener/service/AuditService.java
@@ -0,0 +1,54 @@
+package com.example.shortener.service;
+
+import com.example.shortener.domain.AuditEvent;
+import com.example.shortener.repository.AuditEventRepository;
+import org.slf4j.Logger;
+import org.slf4j.LoggerFactory;
+import org.springframework.stereotype.Service;
+import org.springframework.transaction.annotation.Propagation;
+import org.springframework.transaction.annotation.Transactional;
+
+import java.time.Instant;
+
+@Service
+public class AuditService {
+    private static final Logger log = LoggerFactory.getLogger(AuditService.class);
+    private final AuditEventRepository repository;
+
+    public AuditService(AuditEventRepository repository) {
+        this.repository = repository;
+    }
+
+    /**
+     * Records an event in the caller's transaction. Successful state-changing
+     * operations use this method so the audit and state change commit together.
+     */
+    @Transactional
+    public void record(String action, String code, String clientIp, String outcome) {
+        save(action, code, clientIp, outcome);
+    }
+
+    /**
+     * Records a failure or rejection independently of the failed request
+     * transaction, ensuring that the audit survives a subsequent rollback.
+     */
+    @Transactional(propagation = Propagation.REQUIRES_NEW)
+    public void recordFailure(String action, String code, String clientIp, String outcome) {
+        save(action, code, clientIp, outcome);
+    }
+
+    private void save(String action, String code, String clientIp, String outcome) {
+        try {
+            repository.save(new AuditEvent(
+                    Instant.now(),
+                    action,
+                    code,
+                    clientIp == null ? "unknown" : clientIp,
+                    outcome));
+        } catch (RuntimeException ex) {
+            log.error("Audit persistence failed action={} code={} error={}",
+                    action, code, ex.getClass().getSimpleName());
+            throw ex;
+        }
+    }
+}
--- a/src/main/java/com/example/shortener/service/LinkService.java
+++ b/src/main/java/com/example/shortener/service/LinkService.java
@@ -0,0 +1,122 @@
+package com.example.shortener.service;
+
+import com.example.shortener.api.LinkResponse;
+import com.example.shortener.domain.Link;
+import com.example.shortener.exception.CodeGenerationException;
+import com.example.shortener.exception.LinkNotFoundException;
+import com.example.shortener.repository.LinkRepository;
+import jakarta.servlet.http.HttpServletRequest;
+import org.slf4j.Logger;
+import org.slf4j.LoggerFactory;
+import org.springframework.dao.DataIntegrityViolationException;
+import org.springframework.stereotype.Service;
+import org.springframework.transaction.PlatformTransactionManager;
+import org.springframework.transaction.TransactionDefinition;
+import org.springframework.transaction.annotation.Transactional;
+import org.springframework.transaction.support.TransactionTemplate;
+
+import java.time.Instant;
+import java.util.regex.Pattern;
+
+@Service
+public class LinkService {
+    private static final Logger log = LoggerFactory.getLogger(LinkService.class);
+    private static final int MAX_ATTEMPTS = 10;
+    private static final int CODE_LENGTH = 7;
+    private static final Pattern VALID_CODE = Pattern.compile("[A-Za-z0-9_-]{7}");
+
+    private final LinkRepository links;
+    private final AuditService audit;
+    private final UrlValidator validator;
+    private final ShortCodeGenerator generator;
+    private final ShortUrlBuilder urlBuilder;
+    private final TransactionTemplate creationTransaction;
+
+    public LinkService(LinkRepository links, AuditService audit, UrlValidator validator,
+                       ShortCodeGenerator generator, ShortUrlBuilder urlBuilder,
+                       PlatformTransactionManager transactionManager) {
+        this.links = links;
+        this.audit = audit;
+        this.validator = validator;
+        this.generator = generator;
+        this.urlBuilder = urlBuilder;
+        this.creationTransaction = new TransactionTemplate(transactionManager);
+        this.creationTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
+    }
+
+    public LinkResponse create(String originalUrl, HttpServletRequest request) {
+        validator.validate(originalUrl);
+        Link link = null;
+        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
+            String code = generator.generate();
+            try {
+                Link candidate = creationTransaction.execute(status -> {
+                    Link saved = links.saveAndFlush(new Link(code, originalUrl, Instant.now()));
+                    audit.record("LINK_CREATED", saved.getCode(), request.getRemoteAddr(), "SUCCESS");
+                    return saved;
+                });
+                if (candidate != null) {
+                    link = candidate;
+                    break;
+                }
+            } catch (DataIntegrityViolationException ex) {
+                log.warn("Code collision during LINK_CREATED attempt={}", attempt + 1);
+            }
+        }
+        if (link == null) {
+            log.error("Short-code generation exhausted retry limit");
+            throw new CodeGenerationException("Unable to create a short link");
+        }
+        log.info("LINK_CREATED code={} outcome=SUCCESS", link.getCode());
+        return response(link, request);
+    }
+
+    private void validateCodeAndAudit(String code, HttpServletRequest request) {
+        if (!isValidCode(code)) {
+            audit.recordFailure("UNKNOWN_CODE", auditCode(code), request.getRemoteAddr(), "NOT_FOUND");
+            log.warn("UNKNOWN_CODE outcome=NOT_FOUND invalid_code=true");
+            throw new LinkNotFoundException();
+        }
+    }
+
+    private boolean isValidCode(String code) {
+        return code != null && VALID_CODE.matcher(code).matches();
+    }
+
+    private String auditCode(String code) {
+        if (code == null) {
+            return null;
+        }
+        return code.substring(0, Math.min(CODE_LENGTH, code.length()));
+    }
+
+    @Transactional
+    public LinkResponse details(String code, HttpServletRequest request) {
+        validateCodeAndAudit(code, request);
+        Link link = links.findByCode(code).orElseThrow(() -> missing(code, request));
+        return response(link, request);
+    }
+
+    @Transactional
+    public String resolve(String code, HttpServletRequest request) {
+        validateCodeAndAudit(code, request);
+        if (links.incrementClickCount(code) != 1) {
+            throw missing(code, request);
+        }
+        Link link = links.findByCode(code).orElseThrow(() -> missing(code, request));
+        audit.record("REDIRECTED", code, request.getRemoteAddr(), "REDIRECTED");
+        log.info("REDIRECTED code={} outcome=REDIRECTED", code);
+        return link.getOriginalUrl();
+    }
+
+    private LinkNotFoundException missing(String code, HttpServletRequest request) {
+        audit.recordFailure("UNKNOWN_CODE", code, request.getRemoteAddr(), "NOT_FOUND");
+        log.warn("UNKNOWN_CODE code={} outcome=NOT_FOUND", code);
+        return new LinkNotFoundException();
+    }
+
+    private LinkResponse response(Link link, HttpServletRequest request) {
+        return new LinkResponse(link.getCode(), urlBuilder.build(link.getCode(), request),
+                link.getOriginalUrl(), link.getClickCount());
+    }
+}
--- a/src/main/java/com/example/shortener/service/ShortCodeGenerator.java
+++ b/src/main/java/com/example/shortener/service/ShortCodeGenerator.java
@@ -0,0 +1,20 @@
+package com.example.shortener.service;
+
+import org.springframework.stereotype.Component;
+
+import java.security.SecureRandom;
+
+@Component
+public class ShortCodeGenerator {
+    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
+    private static final int LENGTH = 7;
+    private final SecureRandom random = new SecureRandom();
+
+    public String generate() {
+        StringBuilder code = new StringBuilder(LENGTH);
+        for (int i = 0; i < LENGTH; i++) {
+            code.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
+        }
+        return code.toString();
+    }
+}
--- a/src/main/java/com/example/shortener/service/ShortUrlBuilder.java
+++ b/src/main/java/com/example/shortener/service/ShortUrlBuilder.java
@@ -0,0 +1,28 @@
+package com.example.shortener.service;
+
+import jakarta.servlet.http.HttpServletRequest;
+import org.springframework.beans.factory.annotation.Value;
+import org.springframework.stereotype.Component;
+
+@Component
+public class ShortUrlBuilder {
+    private final String configuredBaseUrl;
+
+    public ShortUrlBuilder(@Value("${shortener.public-base-url:}") String configuredBaseUrl) {
+        this.configuredBaseUrl = configuredBaseUrl;
+    }
+
+    public String build(String code, HttpServletRequest request) {
+        String base = configuredBaseUrl == null || configuredBaseUrl.isBlank()
+                ? request.getScheme() + "://" + request.getServerName()
+                    + port(request) + request.getContextPath()
+                : configuredBaseUrl.replaceAll("/$", "");
+        return base + "/" + code;
+    }
+
+    private String port(HttpServletRequest request) {
+        int port = request.getServerPort();
+        return (request.isSecure() && port == 443) || (!request.isSecure() && port == 80)
+                ? "" : ":" + port;
+    }
+}
--- a/src/main/java/com/example/shortener/service/UrlValidator.java
+++ b/src/main/java/com/example/shortener/service/UrlValidator.java
@@ -0,0 +1,40 @@
+package com.example.shortener.service;
+
+import com.example.shortener.exception.InvalidUrlException;
+import org.slf4j.Logger;
+import org.slf4j.LoggerFactory;
+import org.springframework.stereotype.Component;
+
+import java.net.URI;
+import java.net.URISyntaxException;
+import java.util.Locale;
+
+@Component
+public class UrlValidator {
+    private static final Logger log = LoggerFactory.getLogger(UrlValidator.class);
+    private static final int MAX_URL_LENGTH = 2048;
+
+    public void validate(String value) {
+        if (value == null || value.isBlank() || value.length() > MAX_URL_LENGTH) {
+            reject();
+        }
+        try {
+            URI uri = new URI(value);
+            String scheme = uri.getScheme();
+            if (!uri.isAbsolute() || scheme == null
+                    || !(scheme.toLowerCase(Locale.ROOT).equals("http")
+                    || scheme.toLowerCase(Locale.ROOT).equals("https"))
+                    || uri.getAuthority() == null || uri.getHost() == null
+                    || uri.getHost().isBlank()) {
+                reject();
+            }
+        } catch (URISyntaxException | IllegalArgumentException ex) {
+            reject();
+        }
+    }
+
+    private void reject() {
+        log.warn("CREATION_REJECTED outcome=INVALID_URL");
+        throw new InvalidUrlException("url must be an absolute HTTP or HTTPS URL");
+    }
+}
--- a/src/main/java/com/example/shortener/exception/CodeGenerationException.java
+++ b/src/main/java/com/example/shortener/exception/CodeGenerationException.java
@@ -0,0 +1,7 @@
+package com.example.shortener.exception;
+
+public class CodeGenerationException extends RuntimeException {
+    public CodeGenerationException(String message) {
+        super(message);
+    }
+}
```

