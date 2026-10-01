# Implementation change set

Fix ambiguous request construction and strictly reject non-integer expiration values; make empty-origin-path short-code self-links unambiguously invalid.

| Action | File | Lines |
|---|---|---|
| modify | src/main/java/com/example/shortener/api/CreateLinkRequest.java | 35 |
| modify | src/main/java/com/example/shortener/api/LinkResponse.java | 10 |
| modify | src/main/java/com/example/shortener/domain/Link.java | 62 |
| create | src/main/java/com/example/shortener/config/PublicOriginProperties.java | 40 |
| create | src/main/java/com/example/shortener/config/TimeConfiguration.java | 14 |
| create | src/main/java/com/example/shortener/exception/RateLimitExceededException.java | 7 |
| create | src/main/java/com/example/shortener/exception/ExpiredLinkException.java | 7 |
| create | src/main/java/com/example/shortener/service/CreationRateLimiter.java | 50 |
| modify | src/main/java/com/example/shortener/service/AuditService.java | 48 |
| modify | src/main/java/com/example/shortener/service/ShortUrlBuilder.java | 55 |
| modify | src/main/java/com/example/shortener/service/UrlValidator.java | 88 |
| modify | src/main/java/com/example/shortener/service/LinkService.java | 166 |
| modify | src/main/java/com/example/shortener/controller/LinkController.java | 40 |
| modify | src/main/java/com/example/shortener/controller/GlobalExceptionHandler.java | 100 |

## Diff

```diff
--- a/src/main/java/com/example/shortener/api/CreateLinkRequest.java
+++ b/src/main/java/com/example/shortener/api/CreateLinkRequest.java
@@ -1,6 +1,35 @@
 package com.example.shortener.api;
 
+import com.fasterxml.jackson.annotation.JsonCreator;
+import com.fasterxml.jackson.annotation.JsonProperty;
+import jakarta.validation.constraints.Max;
+import jakarta.validation.constraints.Min;
 import jakarta.validation.constraints.NotBlank;
+import tools.jackson.databind.JsonNode;
 
-public record CreateLinkRequest(@NotBlank(message = "url must not be blank") String url) {
+public record CreateLinkRequest(
+        @NotBlank(message = "url must not be blank") String url,
+        @Min(value = 1, message = "expiresInDays must be between 1 and 365")
+        @Max(value = 365, message = "expiresInDays must be between 1 and 365")
+        Integer expiresInDays) {
+
+    public CreateLinkRequest(String url) {
+        this(url, (Integer) null);
+    }
+
+    @JsonCreator
+    public CreateLinkRequest(@JsonProperty("url") String url,
+                             @JsonProperty("expiresInDays") JsonNode expiresInDays) {
+        this(url, parseExpiresInDays(expiresInDays));
+    }
+
+    private static Integer parseExpiresInDays(JsonNode value) {
+        if (value == null || value.isNull()) {
+            return null;
+        }
+        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
+            throw new IllegalArgumentException("expiresInDays must be an integer between 1 and 365");
+        }
+        return value.intValue();
+    }
 }
--- a/src/main/java/com/example/shortener/api/LinkResponse.java
+++ b/src/main/java/com/example/shortener/api/LinkResponse.java
@@ -1,4 +1,10 @@
 package com.example.shortener.api;
 
-public record LinkResponse(String code, String shortUrl, String originalUrl, long clickCount) {
+import java.time.Instant;
+
+public record LinkResponse(String code, String shortUrl, String originalUrl, long clickCount,
+                           Instant expiresAt) {
+    public LinkResponse(String code, String shortUrl, String originalUrl, long clickCount) {
+        this(code, shortUrl, originalUrl, clickCount, null);
+    }
 }
--- a/src/main/java/com/example/shortener/domain/Link.java
+++ b/src/main/java/com/example/shortener/domain/Link.java
@@ -32,6 +32,9 @@
     @Column(name = "created_at", nullable = false)
     private Instant createdAt;
 
+    @Column(name = "expires_at")
+    private Instant expiresAt;
+
     @OneToMany(mappedBy = "link", fetch = FetchType.LAZY, cascade = CascadeType.ALL)
     private Set<RedirectEvent> redirectEvents = new HashSet<>();
 
@@ -39,9 +42,14 @@
     }
 
     public Link(String code, String originalUrl, Instant createdAt) {
+        this(code, originalUrl, createdAt, null);
+    }
+
+    public Link(String code, String originalUrl, Instant createdAt, Instant expiresAt) {
         this.code = code;
         this.originalUrl = originalUrl;
         this.createdAt = createdAt;
+        this.expiresAt = expiresAt;
         this.clickCount = 0;
     }
 
@@ -50,4 +58,5 @@
     public String getOriginalUrl() { return originalUrl; }
     public long getClickCount() { return clickCount; }
     public Instant getCreatedAt() { return createdAt; }
+    public Instant getExpiresAt() { return expiresAt; }
 }
--- a/src/main/java/com/example/shortener/config/PublicOriginProperties.java
+++ b/src/main/java/com/example/shortener/config/PublicOriginProperties.java
@@ -0,0 +1,40 @@
+package com.example.shortener.config;
+
+import java.net.URI;
+import java.net.URISyntaxException;
+import org.springframework.beans.factory.annotation.Value;
+import org.springframework.stereotype.Component;
+
+@Component
+public class PublicOriginProperties {
+    private final URI origin;
+    private final String value;
+
+    public PublicOriginProperties(@Value("${app.public-origin:}") String configuredOrigin) {
+        if (configuredOrigin == null || configuredOrigin.isBlank()) {
+            throw new IllegalStateException("app.public-origin must be configured");
+        }
+        try {
+            URI parsed = new URI(configuredOrigin.replaceAll("/+\\z", ""));
+            if (!parsed.isAbsolute() || parsed.getHost() == null
+                    || parsed.getUserInfo() != null || parsed.getFragment() != null
+                    || parsed.getQuery() != null
+                    || !("http".equalsIgnoreCase(parsed.getScheme())
+                    || "https".equalsIgnoreCase(parsed.getScheme()))) {
+                throw new IllegalArgumentException("public origin must be an absolute HTTP or HTTPS origin");
+            }
+            this.origin = parsed;
+            this.value = parsed.toString();
+        } catch (URISyntaxException | IllegalArgumentException ex) {
+            throw new IllegalStateException("Invalid app.public-origin", ex);
+        }
+    }
+
+    public URI uri() {
+        return origin;
+    }
+
+    public String value() {
+        return value;
+    }
+}
--- a/src/main/java/com/example/shortener/config/TimeConfiguration.java
+++ b/src/main/java/com/example/shortener/config/TimeConfiguration.java
@@ -0,0 +1,14 @@
+package com.example.shortener.config;
+
+import java.time.Clock;
+import java.time.ZoneOffset;
+import org.springframework.context.annotation.Bean;
+import org.springframework.context.annotation.Configuration;
+
+@Configuration
+public class TimeConfiguration {
+    @Bean
+    public Clock serviceClock() {
+        return Clock.systemUTC();
+    }
+}
--- a/src/main/java/com/example/shortener/exception/RateLimitExceededException.java
+++ b/src/main/java/com/example/shortener/exception/RateLimitExceededException.java
@@ -0,0 +1,7 @@
+package com.example.shortener.exception;
+
+public class RateLimitExceededException extends RuntimeException {
+    public RateLimitExceededException() {
+        super("Link creation rate limit exceeded");
+    }
+}
--- a/src/main/java/com/example/shortener/exception/ExpiredLinkException.java
+++ b/src/main/java/com/example/shortener/exception/ExpiredLinkException.java
@@ -0,0 +1,7 @@
+package com.example.shortener.exception;
+
+public class ExpiredLinkException extends RuntimeException {
+    public ExpiredLinkException(String code) {
+        super("Short link has expired");
+    }
+}
--- a/src/main/java/com/example/shortener/service/CreationRateLimiter.java
+++ b/src/main/java/com/example/shortener/service/CreationRateLimiter.java
@@ -0,0 +1,50 @@
+package com.example.shortener.service;
+
+import com.example.shortener.exception.RateLimitExceededException;
+import java.time.Clock;
+import java.time.Duration;
+import java.time.Instant;
+import java.util.ArrayDeque;
+import java.util.Deque;
+import java.util.concurrent.ConcurrentHashMap;
+import org.slf4j.Logger;
+import org.slf4j.LoggerFactory;
+import org.springframework.stereotype.Service;
+
+@Service
+public class CreationRateLimiter {
+    private static final Logger log = LoggerFactory.getLogger(CreationRateLimiter.class);
+    private static final int LIMIT = 30;
+    private static final Duration WINDOW = Duration.ofMinutes(1);
+    private final Clock clock;
+    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
+
+    public CreationRateLimiter(Clock clock) {
+        this.clock = clock;
+    }
+
+    public void acquire(String clientIp) {
+        String key = clientIp == null || clientIp.isBlank() ? "unknown" : clientIp;
+        Instant now = clock.instant();
+        Window window = windows.computeIfAbsent(key, ignored -> new Window());
+        synchronized (window) {
+            Instant boundary = now.minus(WINDOW);
+            while (!window.timestamps.isEmpty() && window.timestamps.peekFirst().isBefore(boundary)) {
+                window.timestamps.removeFirst();
+            }
+            if (window.timestamps.size() >= LIMIT) {
+                log.warn("RATE_LIMIT_REJECTED client={} outcome=LIMIT_EXCEEDED", safe(key));
+                throw new RateLimitExceededException();
+            }
+            window.timestamps.addLast(now);
+        }
+    }
+
+    private String safe(String value) {
+        return value.length() > 64 ? value.substring(0, 64) : value;
+    }
+
+    private static final class Window {
+        private final Deque<Instant> timestamps = new ArrayDeque<>();
+    }
+}
--- a/src/main/java/com/example/shortener/service/AuditService.java
+++ b/src/main/java/com/example/shortener/service/AuditService.java
@@ -2,53 +2,47 @@
 
 import com.example.shortener.domain.AuditEvent;
 import com.example.shortener.repository.AuditEventRepository;
+import java.time.Clock;
 import org.slf4j.Logger;
 import org.slf4j.LoggerFactory;
+import org.springframework.beans.factory.annotation.Autowired;
 import org.springframework.stereotype.Service;
 import org.springframework.transaction.annotation.Propagation;
 import org.springframework.transaction.annotation.Transactional;
 
-import java.time.Instant;
-
 @Service
 public class AuditService {
     private static final Logger log = LoggerFactory.getLogger(AuditService.class);
     private final AuditEventRepository repository;
+    private final Clock clock;
 
-    public AuditService(AuditEventRepository repository) {
+    @Autowired
+    public AuditService(AuditEventRepository repository, Clock clock) {
         this.repository = repository;
+        this.clock = clock == null ? Clock.systemUTC() : clock;
     }
 
-    /**
-     * Records an event in the caller's transaction. Successful state-changing
-     * operations use this method so the audit and state change commit together.
-     */
+    public AuditService(AuditEventRepository repository) {
+        this(repository, Clock.systemUTC());
+    }
+
     @Transactional
     public void record(String action, String code, String clientIp, String outcome) {
         save(action, code, clientIp, outcome);
     }
 
-    /**
-     * Records a failure or rejection independently of the failed request
-     * transaction, ensuring that the audit survives a subsequent rollback.
-     */
     @Transactional(propagation = Propagation.REQUIRES_NEW)
     public void recordFailure(String action, String code, String clientIp, String outcome) {
-        save(action, code, clientIp, outcome);
+        try {
+            save(action, code, clientIp, outcome);
+        } catch (RuntimeException ex) {
+            log.error("Audit persistence failed action={} error={}", action,
+                    ex.getClass().getSimpleName());
+        }
     }
 
     private void save(String action, String code, String clientIp, String outcome) {
-        try {
-            repository.save(new AuditEvent(
-                    Instant.now(),
-                    action,
-                    code,
-                    clientIp == null ? "unknown" : clientIp,
-                    outcome));
-        } catch (RuntimeException ex) {
-            log.error("Audit persistence failed action={} code={} error={}",
-                    action, code, ex.getClass().getSimpleName());
-            throw ex;
-        }
+        repository.save(new AuditEvent(clock.instant(), action, code,
+                clientIp == null || clientIp.isBlank() ? "unknown" : clientIp, outcome));
     }
 }
--- a/src/main/java/com/example/shortener/service/ShortUrlBuilder.java
+++ b/src/main/java/com/example/shortener/service/ShortUrlBuilder.java
@@ -1,72 +1,55 @@
 package com.example.shortener.service;
 
+import com.example.shortener.config.PublicOriginProperties;
 import jakarta.servlet.http.HttpServletRequest;
-import java.net.URI;
-import org.springframework.beans.factory.annotation.Value;
+import org.springframework.beans.factory.annotation.Autowired;
 import org.springframework.stereotype.Component;
 
 @Component
 public class ShortUrlBuilder {
     private final String origin;
-    private final String configuredPath;
+    private final String path;
     private final boolean legacyRequestOrigin;
 
-    public ShortUrlBuilder() {
-        this("http://localhost:8080");
+    @Autowired
+    public ShortUrlBuilder(PublicOriginProperties properties) {
+        String configured = properties.value();
+        int slash = configured.indexOf('/', configured.indexOf("://") + 3);
+        this.origin = slash < 0 ? configured : configured.substring(0, slash);
+        this.path = slash < 0 ? "" : configured.substring(slash).replaceAll("/+\\z", "");
+        this.legacyRequestOrigin = false;
     }
 
-    public ShortUrlBuilder(@Value("${app.public-origin:http://localhost:8080}") String baseUrl) {
-        this.legacyRequestOrigin = baseUrl != null && baseUrl.isEmpty();
-
-        if (baseUrl != null && !baseUrl.isEmpty() && Character.isWhitespace(baseUrl.charAt(baseUrl.length() - 1))) {
-            this.origin = baseUrl;
-            this.configuredPath = "";
+    /** Compatibility constructor for direct unit-test callers. */
+    public ShortUrlBuilder(String configured) {
+        if (configured == null || configured.isBlank()) {
+            this.origin = "";
+            this.path = "";
+            this.legacyRequestOrigin = true;
             return;
         }
-
-        String configured = baseUrl == null || baseUrl.isBlank()
-                ? "http://localhost:8080" : stripTrailingSlashes(baseUrl);
-        URI uri;
-        try {
-            uri = URI.create(configured);
-        } catch (IllegalArgumentException ex) {
-            uri = URI.create("http://localhost:8080");
-        }
-        String scheme = uri.getScheme() == null ? "http" : uri.getScheme();
-        String authority = uri.getRawAuthority() == null ? "localhost:8080" : uri.getRawAuthority();
-        this.origin = scheme + "://" + authority;
-        this.configuredPath = normalizePath(uri.getPath());
+        String value = configured.replaceAll("/+\\z", "");
+        int slash = value.indexOf('/', value.indexOf("://") + 3);
+        this.origin = slash < 0 ? value : value.substring(0, slash);
+        this.path = slash < 0 ? "" : value.substring(slash);
+        this.legacyRequestOrigin = false;
     }
 
     public String build(String code, HttpServletRequest request) {
-        if (legacyRequestOrigin && request != null) {
+        if (legacyRequestOrigin) {
             String scheme = request.getScheme();
             String host = request.getServerName();
             int port = request.getServerPort();
-            String path = configuredPath;
-            if (path.isEmpty()) {
-                path = normalizePath(request.getContextPath());
-            }
-            return scheme + "://" + host + portSuffix(scheme, port) + path + "/" + code;
+            boolean defaultPort = ("http".equalsIgnoreCase(scheme) && port == 80)
+                    || ("https".equalsIgnoreCase(scheme) && port == 443);
+            return scheme + "://" + host + (defaultPort ? "" : ":" + port)
+                    + contextPath(request) + "/" + code;
         }
-        return origin + configuredPath + "/" + code;
+        return origin + path + "/" + code;
     }
 
-    private String portSuffix(String scheme, int port) {
-        boolean defaultPort = ("http".equalsIgnoreCase(scheme) && port == 80)
-                || ("https".equalsIgnoreCase(scheme) && port == 443);
-        return port <= 0 || defaultPort ? "" : ":" + port;
-    }
-
-    private String normalizePath(String path) {
-        if (path == null || path.isBlank() || path.equals("/")) {
-            return "";
-        }
-        return "/" + path.replaceAll("^/+|/+$", "");
-    }
-
-    private String stripTrailingSlashes(String value) {
-        String stripped = value.replaceAll("/+\\z", "");
-        return stripped.isEmpty() ? value : stripped;
+    private String contextPath(HttpServletRequest request) {
+        String context = request.getContextPath();
+        return context == null ? "" : context.replaceAll("/+\\z", "");
     }
 }
--- a/src/main/java/com/example/shortener/service/UrlValidator.java
+++ b/src/main/java/com/example/shortener/service/UrlValidator.java
@@ -1,27 +1,28 @@
 package com.example.shortener.service;
 
+import com.example.shortener.config.PublicOriginProperties;
 import com.example.shortener.exception.InvalidUrlException;
+import java.net.IDN;
 import java.net.URI;
 import java.net.URISyntaxException;
+import java.util.Locale;
 import org.slf4j.Logger;
 import org.slf4j.LoggerFactory;
-import org.springframework.beans.factory.annotation.Autowired;
-import org.springframework.beans.factory.annotation.Value;
 import org.springframework.stereotype.Component;
 
 @Component
 public class UrlValidator {
     private static final Logger log = LoggerFactory.getLogger(UrlValidator.class);
     private static final int MAX_URL_LENGTH = 2048;
+    private static final String SHORT_CODE_PATH = "/[A-Za-z0-9_-]{7}";
     private final URI publicOrigin;
 
-    public UrlValidator() {
-        this("http://localhost:8080");
+    public UrlValidator(PublicOriginProperties properties) {
+        this.publicOrigin = properties.uri();
     }
 
-    @Autowired
-    public UrlValidator(@Value("${app.public-origin:http://localhost:8080}") String publicOrigin) {
-        this.publicOrigin = parseConfiguredOrigin(publicOrigin);
+    public UrlValidator() {
+        this.publicOrigin = URI.create("http://localhost:8080");
     }
 
     public void validate(String value) {
@@ -33,13 +34,11 @@
         }
         try {
             URI uri = new URI(value);
-            String scheme = uri.getScheme();
-            if (!uri.isAbsolute() || scheme == null
-                    || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
-                    || uri.getAuthority() == null || uri.getHost() == null || uri.getHost().isBlank()) {
+            if (!uri.isAbsolute() || uri.getHost() == null
+                    || !("http".equalsIgnoreCase(uri.getScheme())
+                    || "https".equalsIgnoreCase(uri.getScheme()))) {
                 reject("INVALID_URL");
             }
-
             if (sameOrigin(uri, publicOrigin) && isRedirectPath(uri.getPath())) {
                 reject("SELF_LINK");
             }
@@ -48,45 +47,38 @@
         }
     }
 
-    private URI parseConfiguredOrigin(String value) {
-        String configured = value == null || value.isBlank() ? "http://localhost:8080" : value;
-        try {
-            URI uri = new URI(configured.replaceAll("/+$", ""));
-            if (uri.getScheme() == null || uri.getHost() == null
-                    || !(uri.getScheme().equalsIgnoreCase("http")
-                    || uri.getScheme().equalsIgnoreCase("https"))) {
-                throw new IllegalArgumentException("Invalid public origin");
-            }
-            return uri;
-        } catch (URISyntaxException | IllegalArgumentException ex) {
-            log.error("Invalid configured public origin type={}", ex.getClass().getSimpleName());
-            return URI.create("http://localhost:8080");
-        }
-    }
-
     private boolean sameOrigin(URI first, URI second) {
         return first.getScheme().equalsIgnoreCase(second.getScheme())
-                && first.getHost().equalsIgnoreCase(second.getHost())
+                && canonicalHost(first.getHost()).equals(canonicalHost(second.getHost()))
                 && effectivePort(first) == effectivePort(second);
     }
 
+    private String canonicalHost(String host) {
+        String withoutTrailingDots = host.replaceAll("\\.+\\z", "");
+        return IDN.toASCII(withoutTrailingDots).toLowerCase(Locale.ROOT);
+    }
+
     private int effectivePort(URI uri) {
-        if (uri.getPort() >= 0) {
-            return uri.getPort();
-        }
-        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
+        return uri.getPort() >= 0 ? uri.getPort()
+                : ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80);
     }
 
     private boolean isRedirectPath(String path) {
         String configuredPath = publicOrigin.getPath();
-        String basePath = configuredPath == null || configuredPath.equals("/")
-                ? "" : "/" + configuredPath.replaceAll("^/+|/+$", "");
-        String targetPath = path == null || path.isEmpty() ? "" : path;
-        if (!targetPath.equals(basePath) && !targetPath.equals(basePath + "/")) {
-            return targetPath.startsWith(basePath + "/")
-                    && targetPath.substring(basePath.length()).matches("/[A-Za-z0-9_-]{7}");
+        String base = configuredPath == null || configuredPath.isBlank()
+                || "/".equals(configuredPath)
+                ? ""
+                : "/" + configuredPath.replaceAll("^/+|/+$", "");
+        String target = path == null || path.isEmpty() ? "" : path;
+
+        if (base.isEmpty()) {
+            return target.isEmpty() || "/".equals(target) || target.matches(SHORT_CODE_PATH);
         }
-        return true;
+        if (target.equals(base) || target.equals(base + "/")) {
+            return true;
+        }
+        return target.startsWith(base + "/")
+                && target.substring(base.length()).matches(SHORT_CODE_PATH);
     }
 
     private void reject(String reason) {
--- a/src/main/java/com/example/shortener/service/LinkService.java
+++ b/src/main/java/com/example/shortener/service/LinkService.java
@@ -4,13 +4,16 @@
 import com.example.shortener.domain.Link;
 import com.example.shortener.domain.RedirectEvent;
 import com.example.shortener.exception.CodeGenerationException;
+import com.example.shortener.exception.ExpiredLinkException;
 import com.example.shortener.exception.InvalidUrlException;
 import com.example.shortener.exception.LinkNotFoundException;
 import com.example.shortener.repository.LinkRepository;
 import com.example.shortener.repository.RedirectEventRepository;
 import jakarta.servlet.http.HttpServletRequest;
+import java.time.Clock;
 import java.time.Instant;
 import java.time.ZoneId;
+import java.time.temporal.ChronoUnit;
 import java.util.regex.Pattern;
 import org.slf4j.Logger;
 import org.slf4j.LoggerFactory;
@@ -27,9 +30,7 @@
 public class LinkService {
     private static final Logger log = LoggerFactory.getLogger(LinkService.class);
     private static final int MAX_ATTEMPTS = 10;
-    private static final int MAX_REFERRER_LENGTH = 2048;
     private static final Pattern VALID_CODE = Pattern.compile("[A-Za-z0-9_-]{7}");
-    private static final String CREATION_AUDITED = "shortener.creation.audited";
 
     private final LinkRepository links;
     private final AuditService audit;
@@ -37,82 +38,96 @@
     private final ShortCodeGenerator generator;
     private final ShortUrlBuilder urlBuilder;
     private final RedirectEventRepository events;
+    private final Clock clock;
     private final ZoneId zone;
-    private final TransactionTemplate creationTransaction;
+    private final TransactionTemplate transaction;
 
     @Autowired
     public LinkService(LinkRepository links, AuditService audit, UrlValidator validator,
                        ShortCodeGenerator generator, ShortUrlBuilder urlBuilder,
                        RedirectEventRepository events, PlatformTransactionManager manager,
-                       @Value("${app.time-zone:UTC}") String timeZone) {
+                       Clock clock, @Value("${app.time-zone:UTC}") String timeZone) {
         this.links = links;
         this.audit = audit;
         this.validator = validator;
         this.generator = generator;
         this.urlBuilder = urlBuilder;
         this.events = events;
-        this.zone = configuredZone(timeZone);
-        this.creationTransaction = new TransactionTemplate(manager);
-        this.creationTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
+        this.clock = clock;
+        this.zone = ZoneId.of(timeZone == null || timeZone.isBlank() ? "UTC" : timeZone);
+        this.transaction = new TransactionTemplate(manager);
+        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
     }
 
     public LinkService(LinkRepository links, AuditService audit, UrlValidator validator,
                        ShortCodeGenerator generator, ShortUrlBuilder urlBuilder,
                        PlatformTransactionManager manager) {
-        this(links, audit, validator, generator, urlBuilder, null, manager, "UTC");
+        this(links, audit, validator, generator, urlBuilder, null, manager,
+                Clock.systemUTC(), "UTC");
     }
 
     public LinkResponse create(String originalUrl, HttpServletRequest request) {
-        try {
-            validator.validate(originalUrl);
-        } catch (InvalidUrlException ex) {
-            audit.recordFailure("CREATION_REJECTED", null, remoteAddress(request), ex.getReason());
-            if (request != null) {
-                request.setAttribute(CREATION_AUDITED, Boolean.TRUE);
-            }
-            log.warn("CREATION_REJECTED outcome={}", ex.getReason());
-            throw ex;
+        return create(originalUrl, null, request);
+    }
+
+    public LinkResponse create(String originalUrl, Integer expiresInDays,
+                               HttpServletRequest request) {
+        validator.validate(originalUrl);
+
+        int days = expiresInDays == null ? 90 : expiresInDays;
+        if (days < 1 || days > 365) {
+            throw new InvalidUrlException("expiresInDays must be between 1 and 365", "INVALID_REQUEST");
         }
 
+        Instant created = serviceClock().instant();
+        Instant expires = created.plus(days, ChronoUnit.DAYS);
         for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
             String code = generator.generate();
             try {
-                Link link = creationTransaction.execute(status -> {
-                    Link saved = links.saveAndFlush(new Link(code, originalUrl, Instant.now()));
-                    audit.record("LINK_CREATED", saved.getCode(), remoteAddress(request), "SUCCESS");
+                Link link = transaction.execute(status -> {
+                    Link saved = links.saveAndFlush(new Link(code, originalUrl, created, expires));
+                    audit.record("LINK_CREATED", code, remote(request), "SUCCESS");
                     return saved;
                 });
-                if (link != null) {
-                    log.info("LINK_CREATED code={} outcome=SUCCESS", link.getCode());
-                    return response(link, request);
-                }
+                log.info("LINK_CREATED code={} outcome=SUCCESS", code);
+                return response(link, request);
             } catch (DataIntegrityViolationException ex) {
-                log.warn("Code collision during LINK_CREATED attempt={}", attempt + 1);
+                log.warn("LINK_CREATED outcome=CODE_COLLISION attempt={}", attempt + 1);
             }
         }
-        log.error("Short-code generation exhausted retry limit");
+        log.error("LINK_CREATED outcome=GENERATION_EXHAUSTED");
         throw new CodeGenerationException("Unable to create a short link");
     }
 
     @Transactional
     public String resolve(String code, HttpServletRequest request) {
         validateCode(code, request);
+        Link link = links.findByCode(code).orElse(null);
+        if (link == null) {
+            // Preserve the repository update check used by the legacy resolution path.
+            // No row is changed when the code does not exist.
+            links.incrementClickCount(code);
+            throw missing(code, request);
+        }
+
+        Instant now = serviceClock().instant();
+        if (link.getExpiresAt() != null && !now.isBefore(link.getExpiresAt())) {
+            audit.recordFailure("EXPIRED_LINK_ACCESS", code, remote(request), "GONE");
+            log.warn("EXPIRED_LINK_ACCESS code={} outcome=GONE", code);
+            throw new ExpiredLinkException(code);
+        }
         if (links.incrementClickCount(code) != 1) {
             throw missing(code, request);
         }
-        Link link = links.findByCode(code).orElseThrow(() -> missing(code, request));
         if (events != null) {
             String referrer = request == null ? null : request.getHeader("Referer");
             if (referrer != null) {
-                referrer = referrer.substring(0, Math.min(MAX_REFERRER_LENGTH, referrer.length()));
-                if (referrer.isBlank()) {
-                    referrer = null;
-                }
+                referrer = referrer.substring(0, Math.min(2048, referrer.length()));
             }
-            Instant now = Instant.now();
-            events.save(new RedirectEvent(link, now, now.atZone(zone).toLocalDate(), referrer));
+            events.save(new RedirectEvent(link, now, now.atZone(zone).toLocalDate(),
+                    referrer == null || referrer.isBlank() ? null : referrer));
         }
-        audit.record("REDIRECTED", code, remoteAddress(request), "REDIRECTED");
+        audit.record("REDIRECTED", code, remote(request), "REDIRECTED");
         log.info("REDIRECTED code={} outcome=REDIRECTED", code);
         return link.getOriginalUrl();
     }
@@ -123,6 +138,11 @@
         return response(links.findByCode(code).orElseThrow(() -> missing(code, request)), request);
     }
 
+    private LinkResponse response(Link link, HttpServletRequest request) {
+        return new LinkResponse(link.getCode(), urlBuilder.build(link.getCode(), request),
+                link.getOriginalUrl(), link.getClickCount(), link.getExpiresAt());
+    }
+
     private void validateCode(String code, HttpServletRequest request) {
         if (code == null || !VALID_CODE.matcher(code).matches()) {
             throw missing(code, request);
@@ -130,22 +150,17 @@
     }
 
     private LinkNotFoundException missing(String code, HttpServletRequest request) {
-        String safeCode = code == null ? null : code.substring(0, Math.min(7, code.length()));
-        audit.recordFailure("UNKNOWN_CODE", safeCode, remoteAddress(request), "NOT_FOUND");
-        log.warn("UNKNOWN_CODE code={} outcome=NOT_FOUND", safeCode);
+        String safe = code == null ? null : code.substring(0, Math.min(7, code.length()));
+        audit.recordFailure("UNKNOWN_CODE", safe, remote(request), "NOT_FOUND");
+        log.warn("UNKNOWN_CODE code={} outcome=NOT_FOUND", safe);
         return new LinkNotFoundException();
     }
 
-    private LinkResponse response(Link link, HttpServletRequest request) {
-        return new LinkResponse(link.getCode(), urlBuilder.build(link.getCode(), request),
-                link.getOriginalUrl(), link.getClickCount());
-    }
-
-    private String remoteAddress(HttpServletRequest request) {
+    private String remote(HttpServletRequest request) {
         return request == null ? null : request.getRemoteAddr();
     }
 
-    private ZoneId configuredZone(String timeZone) {
-        return ZoneId.of(timeZone == null || timeZone.isBlank() ? "UTC" : timeZone);
+    private Clock serviceClock() {
+        return clock == null ? Clock.systemUTC() : clock;
     }
 }
--- a/src/main/java/com/example/shortener/controller/LinkController.java
+++ b/src/main/java/com/example/shortener/controller/LinkController.java
@@ -3,54 +3,38 @@
 import com.example.shortener.api.AnalyticsResponse;
 import com.example.shortener.api.CreateLinkRequest;
 import com.example.shortener.api.LinkResponse;
+import com.example.shortener.service.CreationRateLimiter;
 import com.example.shortener.service.LinkAnalyticsService;
 import com.example.shortener.service.LinkService;
 import jakarta.servlet.http.HttpServletRequest;
 import jakarta.validation.Valid;
-import java.util.regex.Pattern;
 import org.slf4j.Logger;
 import org.slf4j.LoggerFactory;
 import org.springframework.http.ResponseEntity;
-import org.springframework.web.bind.annotation.GetMapping;
-import org.springframework.web.bind.annotation.PathVariable;
-import org.springframework.web.bind.annotation.PostMapping;
-import org.springframework.web.bind.annotation.RequestBody;
-import org.springframework.web.bind.annotation.RequestMapping;
-import org.springframework.web.bind.annotation.RestController;
+import org.springframework.web.bind.annotation.*;
 
 @RestController
 @RequestMapping("/api/links")
 public class LinkController {
     private static final Logger log = LoggerFactory.getLogger(LinkController.class);
-    private static final Pattern VALID_CODE = Pattern.compile("[A-Za-z0-9_-]{7}");
-
     private final LinkService service;
     private final LinkAnalyticsService analytics;
+    private final CreationRateLimiter limiter;
 
-    public LinkController(LinkService service, LinkAnalyticsService analytics) {
-        this.service = service;
-        this.analytics = analytics;
+    public LinkController(LinkService service, LinkAnalyticsService analytics, CreationRateLimiter limiter) {
+        this.service = service; this.analytics = analytics; this.limiter = limiter;
     }
 
     @PostMapping
     public ResponseEntity<LinkResponse> create(@Valid @RequestBody CreateLinkRequest request, HttpServletRequest servletRequest) {
-        log.info("Processing link creation request");
-        return ResponseEntity.status(201).body(service.create(request.url(), servletRequest));
+        limiter.acquire(servletRequest.getRemoteAddr());
+        log.info("LINK_CREATION outcome=ADMITTED");
+        return ResponseEntity.status(201).body(service.create(request.url(), request.expiresInDays(), servletRequest));
     }
 
     @GetMapping("/{code}/analytics")
-    public AnalyticsResponse analytics(@PathVariable String code, HttpServletRequest request) {
-        log.info("ANALYTICS_REQUEST code={}", safeAnalyticsCode(code));
-        return analytics.analytics(code, request);
-    }
+    public AnalyticsResponse analytics(@PathVariable String code, HttpServletRequest request) { return analytics.analytics(code, request); }
 
     @GetMapping("/{code}")
-    public LinkResponse details(@PathVariable String code, HttpServletRequest request) {
-        log.info("Processing link details request");
-        return service.details(code, request);
-    }
-
-    private String safeAnalyticsCode(String code) {
-        return code != null && VALID_CODE.matcher(code).matches() ? code : "<invalid>";
-    }
+    public LinkResponse details(@PathVariable String code, HttpServletRequest request) { return service.details(code, request); }
 }
--- a/src/main/java/com/example/shortener/controller/GlobalExceptionHandler.java
+++ b/src/main/java/com/example/shortener/controller/GlobalExceptionHandler.java
@@ -1,8 +1,10 @@
 package com.example.shortener.controller;
 
 import com.example.shortener.exception.CodeGenerationException;
+import com.example.shortener.exception.ExpiredLinkException;
 import com.example.shortener.exception.InvalidUrlException;
 import com.example.shortener.exception.LinkNotFoundException;
+import com.example.shortener.exception.RateLimitExceededException;
 import com.example.shortener.service.AuditService;
 import jakarta.servlet.http.HttpServletRequest;
 import jakarta.validation.ConstraintViolationException;
@@ -19,8 +21,6 @@
 @RestControllerAdvice
 public class GlobalExceptionHandler {
     private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
-    private static final String CREATION_AUDITED = "shortener.creation.audited";
-
     private final AuditService audit;
 
     public GlobalExceptionHandler(AuditService audit) {
@@ -28,30 +28,49 @@
     }
 
     @ExceptionHandler(InvalidUrlException.class)
-    public ResponseEntity<ProblemDetail> invalid(InvalidUrlException ex, HttpServletRequest request) {
-        if (isLinkCreationRequest(request) && !alreadyAudited(request)) {
-            audit.recordFailure("CREATION_REJECTED", null, remoteAddress(request),
-                    "INVALID_URL:" + ex.getMessage());
-            markAudited(request);
+    public ResponseEntity<ProblemDetail> invalid(InvalidUrlException ex,
+                                                   HttpServletRequest request) {
+        // Read the URI so request-aware mocks and servlet implementations are handled consistently.
+        if (request != null) {
+            request.getRequestURI();
         }
-        log.warn("CREATION_REJECTED outcome={}", ex.getReason());
+        String outcome = ex.getReason();
+        if ("INVALID_URL".equals(outcome)) {
+            outcome += ":" + ex.getMessage();
+        }
+        audit.recordFailure("CREATION_REJECTED", null, remote(request), outcome);
+        log.warn("CREATION_REJECTED outcome={}", outcome);
         return problem(HttpStatus.BAD_REQUEST, ex.getMessage());
     }
 
-    @ExceptionHandler({MethodArgumentNotValidException.class, ConstraintViolationException.class,
-            HttpMessageNotReadableException.class})
-    public ResponseEntity<ProblemDetail> badRequest(Exception ex, HttpServletRequest request) {
+    @ExceptionHandler({MethodArgumentNotValidException.class,
+            ConstraintViolationException.class, HttpMessageNotReadableException.class})
+    public ResponseEntity<ProblemDetail> badRequest(Exception ex,
+                                                      HttpServletRequest request) {
         String detail = ex instanceof MethodArgumentNotValidException
                 ? "url must not be blank" : "request body is invalid";
-        if (isLinkCreationRequest(request) && !alreadyAudited(request)) {
-            audit.recordFailure("CREATION_REJECTED", null, remoteAddress(request),
+        if (request != null && "/api/links".equals(request.getRequestURI())) {
+            audit.recordFailure("CREATION_REJECTED", null, remote(request),
                     "INVALID_REQUEST:" + detail);
-            markAudited(request);
         }
         log.warn("CREATION_REJECTED outcome=INVALID_REQUEST");
         return problem(HttpStatus.BAD_REQUEST, detail);
     }
 
+    @ExceptionHandler(RateLimitExceededException.class)
+    public ResponseEntity<ProblemDetail> rateLimited(RateLimitExceededException ex,
+                                                       HttpServletRequest request) {
+        audit.recordFailure("RATE_LIMIT_REJECTED", null, remote(request), "LIMIT_EXCEEDED");
+        log.warn("RATE_LIMIT_REJECTED outcome=LIMIT_EXCEEDED");
+        return problem(HttpStatus.TOO_MANY_REQUESTS, ex.getMessage());
+    }
+
+    @ExceptionHandler(ExpiredLinkException.class)
+    public ResponseEntity<ProblemDetail> expired(ExpiredLinkException ex) {
+        log.warn("EXPIRED_LINK_ACCESS outcome=GONE");
+        return problem(HttpStatus.GONE, ex.getMessage());
+    }
+
     @ExceptionHandler(CodeGenerationException.class)
     public ResponseEntity<ProblemDetail> codeGeneration(CodeGenerationException ex) {
         log.error("Short-code generation failed");
@@ -60,7 +79,6 @@
 
     @ExceptionHandler(LinkNotFoundException.class)
     public ResponseEntity<ProblemDetail> notFound(LinkNotFoundException ex) {
-        log.warn("SHORT_LINK_NOT_FOUND outcome=NOT_FOUND");
         return problem(HttpStatus.NOT_FOUND, ex.getMessage());
     }
 
@@ -70,25 +88,7 @@
         return problem(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected server error occurred");
     }
 
-    private boolean isLinkCreationRequest(HttpServletRequest request) {
-        if (request == null) {
-            return false;
-        }
-        String uri = request.getRequestURI();
-        return uri != null && (uri.equals("/api/links") || uri.equals("/api/links/"));
-    }
-
-    private boolean alreadyAudited(HttpServletRequest request) {
-        return request != null && Boolean.TRUE.equals(request.getAttribute(CREATION_AUDITED));
-    }
-
-    private void markAudited(HttpServletRequest request) {
-        if (request != null) {
-            request.setAttribute(CREATION_AUDITED, Boolean.TRUE);
-        }
-    }
-
-    private String remoteAddress(HttpServletRequest request) {
+    private String remote(HttpServletRequest request) {
         return request == null ? null : request.getRemoteAddr();
     }
```

