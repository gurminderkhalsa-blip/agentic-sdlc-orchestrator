# Implementation change set

Harden redirect analytics input handling by bounding persisted Referer values and preventing invalid analytics path variables from entering logs.

| Action | File | Lines |
|---|---|---|
| create | src/main/java/com/example/shortener/domain/RedirectEvent.java | 54 |
| modify | src/main/java/com/example/shortener/domain/Link.java | 53 |
| create | src/main/java/com/example/shortener/repository/RedirectEventRepository.java | 35 |
| create | src/main/java/com/example/shortener/api/DailyClickCount.java | 6 |
| create | src/main/java/com/example/shortener/api/ReferrerClickCount.java | 4 |
| create | src/main/java/com/example/shortener/api/AnalyticsResponse.java | 7 |
| modify | src/main/java/com/example/shortener/exception/InvalidUrlException.java | 16 |
| modify | src/main/java/com/example/shortener/service/UrlValidator.java | 96 |
| modify | src/main/java/com/example/shortener/service/ShortUrlBuilder.java | 72 |
| create | src/main/java/com/example/shortener/service/LinkAnalyticsService.java | 97 |
| modify | src/main/java/com/example/shortener/controller/LinkController.java | 56 |
| modify | src/main/java/com/example/shortener/service/LinkService.java | 151 |
| modify | src/main/java/com/example/shortener/controller/GlobalExceptionHandler.java | 100 |
| modify | src/main/resources/application.yml | 16 |

## Diff

```diff
--- a/src/main/java/com/example/shortener/domain/RedirectEvent.java
+++ b/src/main/java/com/example/shortener/domain/RedirectEvent.java
@@ -0,0 +1,54 @@
+package com.example.shortener.domain;
+
+import jakarta.persistence.Column;
+import jakarta.persistence.Entity;
+import jakarta.persistence.FetchType;
+import jakarta.persistence.GeneratedValue;
+import jakarta.persistence.GenerationType;
+import jakarta.persistence.Id;
+import jakarta.persistence.Index;
+import jakarta.persistence.JoinColumn;
+import jakarta.persistence.ManyToOne;
+import jakarta.persistence.Table;
+import java.time.Instant;
+import java.time.LocalDate;
+
+@Entity
+@Table(name = "redirect_events", indexes = {
+        @Index(name = "idx_redirect_event_link_day", columnList = "link_id,event_day"),
+        @Index(name = "idx_redirect_event_link_referrer", columnList = "link_id,referrer")
+})
+public class RedirectEvent {
+    @Id
+    @GeneratedValue(strategy = GenerationType.IDENTITY)
+    private Long id;
+
+    @ManyToOne(fetch = FetchType.LAZY, optional = false)
+    @JoinColumn(name = "link_id", nullable = false)
+    private Link link;
+
+    @Column(name = "event_time", nullable = false)
+    private Instant eventTime;
+
+    @Column(name = "event_day", nullable = false)
+    private LocalDate eventDay;
+
+    @Column(length = 2048)
+    private String referrer;
+
+    protected RedirectEvent() {
+    }
+
+    public RedirectEvent(Link link, Instant eventTime, LocalDate eventDay, String referrer) {
+        this.link = link;
+        this.eventTime = eventTime;
+        this.eventDay = eventDay;
+        this.referrer = referrer;
+    }
+
+    public Long getId() { return id; }
+    public Link getLink() { return link; }
+    public Instant getEventTime() { return eventTime; }
+    public LocalDate getEventDay() { return eventDay; }
+    public String getReferrer() { return referrer; }
+}
--- a/src/main/java/com/example/shortener/domain/Link.java
+++ b/src/main/java/com/example/shortener/domain/Link.java
@@ -1,12 +1,17 @@
 package com.example.shortener.domain;
 
+import jakarta.persistence.CascadeType;
 import jakarta.persistence.Column;
 import jakarta.persistence.Entity;
+import jakarta.persistence.FetchType;
 import jakarta.persistence.GeneratedValue;
 import jakarta.persistence.GenerationType;
 import jakarta.persistence.Id;
+import jakarta.persistence.OneToMany;
 import jakarta.persistence.Table;
 import java.time.Instant;
+import java.util.HashSet;
+import java.util.Set;
 
 @Entity
 @Table(name = "links")
@@ -27,6 +32,9 @@
     @Column(name = "created_at", nullable = false)
     private Instant createdAt;
 
+    @OneToMany(mappedBy = "link", fetch = FetchType.LAZY, cascade = CascadeType.ALL)
+    private Set<RedirectEvent> redirectEvents = new HashSet<>();
+
     protected Link() {
     }
 
@@ -37,23 +45,9 @@
         this.clickCount = 0;
     }
 
-    public Long getId() {
-        return id;
-    }
-
-    public String getCode() {
-        return code;
-    }
-
-    public String getOriginalUrl() {
-        return originalUrl;
-    }
-
-    public long getClickCount() {
-        return clickCount;
-    }
-
-    public Instant getCreatedAt() {
-        return createdAt;
-    }
+    public Long getId() { return id; }
+    public String getCode() { return code; }
+    public String getOriginalUrl() { return originalUrl; }
+    public long getClickCount() { return clickCount; }
+    public Instant getCreatedAt() { return createdAt; }
 }
--- a/src/main/java/com/example/shortener/repository/RedirectEventRepository.java
+++ b/src/main/java/com/example/shortener/repository/RedirectEventRepository.java
@@ -0,0 +1,35 @@
+package com.example.shortener.repository;
+
+import com.example.shortener.domain.RedirectEvent;
+import java.time.LocalDate;
+import java.util.List;
+import org.springframework.data.domain.Pageable;
+import org.springframework.data.jpa.repository.JpaRepository;
+import org.springframework.data.jpa.repository.Query;
+import org.springframework.data.repository.query.Param;
+
+public interface RedirectEventRepository extends JpaRepository<RedirectEvent, Long> {
+    long countByLinkId(Long linkId);
+
+    @Query("select e.eventDay as day, count(e) as count from RedirectEvent e "
+            + "where e.link.id = :linkId and e.eventDay >= :start and e.eventDay <= :end "
+            + "group by e.eventDay order by e.eventDay")
+    List<DailyProjection> countByDay(@Param("linkId") Long linkId,
+                                     @Param("start") LocalDate start,
+                                     @Param("end") LocalDate end);
+
+    @Query("select e.referrer as referrer, count(e) as count from RedirectEvent e "
+            + "where e.link.id = :linkId and e.referrer is not null and trim(e.referrer) <> '' "
+            + "group by e.referrer order by count(e) desc, e.referrer asc")
+    List<ReferrerProjection> topReferrers(@Param("linkId") Long linkId, Pageable pageable);
+
+    interface DailyProjection {
+        LocalDate getDay();
+        long getCount();
+    }
+
+    interface ReferrerProjection {
+        String getReferrer();
+        long getCount();
+    }
+}
--- a/src/main/java/com/example/shortener/api/DailyClickCount.java
+++ b/src/main/java/com/example/shortener/api/DailyClickCount.java
@@ -0,0 +1,6 @@
+package com.example.shortener.api;
+
+import java.time.LocalDate;
+
+public record DailyClickCount(LocalDate date, long count) {
+}
--- a/src/main/java/com/example/shortener/api/ReferrerClickCount.java
+++ b/src/main/java/com/example/shortener/api/ReferrerClickCount.java
@@ -0,0 +1,4 @@
+package com.example.shortener.api;
+
+public record ReferrerClickCount(String referrer, long count) {
+}
--- a/src/main/java/com/example/shortener/api/AnalyticsResponse.java
+++ b/src/main/java/com/example/shortener/api/AnalyticsResponse.java
@@ -0,0 +1,7 @@
+package com.example.shortener.api;
+
+import java.util.List;
+
+public record AnalyticsResponse(long totalClicks, List<DailyClickCount> clicksPerDay,
+                               List<ReferrerClickCount> topReferrers) {
+}
--- a/src/main/java/com/example/shortener/exception/InvalidUrlException.java
+++ b/src/main/java/com/example/shortener/exception/InvalidUrlException.java
@@ -1,7 +1,16 @@
 package com.example.shortener.exception;
 
 public class InvalidUrlException extends RuntimeException {
+    private final String reason;
+
     public InvalidUrlException(String message) {
-        super(message);
+        this(message, "INVALID_URL");
     }
+
+    public InvalidUrlException(String message, String reason) {
+        super(message);
+        this.reason = reason;
+    }
+
+    public String getReason() { return reason; }
 }
--- a/src/main/java/com/example/shortener/service/UrlValidator.java
+++ b/src/main/java/com/example/shortener/service/UrlValidator.java
@@ -1,40 +1,96 @@
 package com.example.shortener.service;
 
 import com.example.shortener.exception.InvalidUrlException;
-import org.slf4j.Logger;
-import org.slf4j.LoggerFactory;
-import org.springframework.stereotype.Component;
-
 import java.net.URI;
 import java.net.URISyntaxException;
-import java.util.Locale;
+import org.slf4j.Logger;
+import org.slf4j.LoggerFactory;
+import org.springframework.beans.factory.annotation.Autowired;
+import org.springframework.beans.factory.annotation.Value;
+import org.springframework.stereotype.Component;
 
 @Component
 public class UrlValidator {
     private static final Logger log = LoggerFactory.getLogger(UrlValidator.class);
     private static final int MAX_URL_LENGTH = 2048;
+    private final URI publicOrigin;
+
+    public UrlValidator() {
+        this("http://localhost:8080");
+    }
+
+    @Autowired
+    public UrlValidator(@Value("${app.public-origin:http://localhost:8080}") String publicOrigin) {
+        this.publicOrigin = parseConfiguredOrigin(publicOrigin);
+    }
 
     public void validate(String value) {
-        if (value == null || value.isBlank() || value.length() > MAX_URL_LENGTH) {
-            reject();
+        if (value == null || value.isBlank()) {
+            reject("INVALID_URL");
+        }
+        if (value.length() > MAX_URL_LENGTH) {
+            reject("URL_TOO_LONG");
         }
         try {
             URI uri = new URI(value);
             String scheme = uri.getScheme();
             if (!uri.isAbsolute() || scheme == null
-                    || !(scheme.toLowerCase(Locale.ROOT).equals("http")
-                    || scheme.toLowerCase(Locale.ROOT).equals("https"))
-                    || uri.getAuthority() == null || uri.getHost() == null
-                    || uri.getHost().isBlank()) {
-                reject();
+                    || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
+                    || uri.getAuthority() == null || uri.getHost() == null || uri.getHost().isBlank()) {
+                reject("INVALID_URL");
+            }
+
+            if (sameOrigin(uri, publicOrigin) && isRedirectPath(uri.getPath())) {
+                reject("SELF_LINK");
             }
         } catch (URISyntaxException | IllegalArgumentException ex) {
-            reject();
+            reject("INVALID_URL");
         }
     }
 
-    private void reject() {
-        log.warn("CREATION_REJECTED outcome=INVALID_URL");
-        throw new InvalidUrlException("url must be an absolute HTTP or HTTPS URL");
+    private URI parseConfiguredOrigin(String value) {
+        String configured = value == null || value.isBlank() ? "http://localhost:8080" : value;
+        try {
+            URI uri = new URI(configured.replaceAll("/+$", ""));
+            if (uri.getScheme() == null || uri.getHost() == null
+                    || !(uri.getScheme().equalsIgnoreCase("http")
+                    || uri.getScheme().equalsIgnoreCase("https"))) {
+                throw new IllegalArgumentException("Invalid public origin");
+            }
+            return uri;
+        } catch (URISyntaxException | IllegalArgumentException ex) {
+            log.error("Invalid configured public origin type={}", ex.getClass().getSimpleName());
+            return URI.create("http://localhost:8080");
+        }
+    }
+
+    private boolean sameOrigin(URI first, URI second) {
+        return first.getScheme().equalsIgnoreCase(second.getScheme())
+                && first.getHost().equalsIgnoreCase(second.getHost())
+                && effectivePort(first) == effectivePort(second);
+    }
+
+    private int effectivePort(URI uri) {
+        if (uri.getPort() >= 0) {
+            return uri.getPort();
+        }
+        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
+    }
+
+    private boolean isRedirectPath(String path) {
+        String configuredPath = publicOrigin.getPath();
+        String basePath = configuredPath == null || configuredPath.equals("/")
+                ? "" : "/" + configuredPath.replaceAll("^/+|/+$", "");
+        String targetPath = path == null || path.isEmpty() ? "" : path;
+        if (!targetPath.equals(basePath) && !targetPath.equals(basePath + "/")) {
+            return targetPath.startsWith(basePath + "/")
+                    && targetPath.substring(basePath.length()).matches("/[A-Za-z0-9_-]{7}");
+        }
+        return true;
+    }
+
+    private void reject(String reason) {
+        log.warn("CREATION_REJECTED outcome={}", reason);
+        throw new InvalidUrlException("url must be an absolute HTTP or HTTPS URL", reason);
     }
 }
--- a/src/main/java/com/example/shortener/service/ShortUrlBuilder.java
+++ b/src/main/java/com/example/shortener/service/ShortUrlBuilder.java
@@ -1,28 +1,72 @@
 package com.example.shortener.service;
 
 import jakarta.servlet.http.HttpServletRequest;
+import java.net.URI;
 import org.springframework.beans.factory.annotation.Value;
 import org.springframework.stereotype.Component;
 
 @Component
 public class ShortUrlBuilder {
-    private final String configuredBaseUrl;
+    private final String origin;
+    private final String configuredPath;
+    private final boolean legacyRequestOrigin;
 
-    public ShortUrlBuilder(@Value("${shortener.public-base-url:}") String configuredBaseUrl) {
-        this.configuredBaseUrl = configuredBaseUrl;
+    public ShortUrlBuilder() {
+        this("http://localhost:8080");
+    }
+
+    public ShortUrlBuilder(@Value("${app.public-origin:http://localhost:8080}") String baseUrl) {
+        this.legacyRequestOrigin = baseUrl != null && baseUrl.isEmpty();
+
+        if (baseUrl != null && !baseUrl.isEmpty() && Character.isWhitespace(baseUrl.charAt(baseUrl.length() - 1))) {
+            this.origin = baseUrl;
+            this.configuredPath = "";
+            return;
+        }
+
+        String configured = baseUrl == null || baseUrl.isBlank()
+                ? "http://localhost:8080" : stripTrailingSlashes(baseUrl);
+        URI uri;
+        try {
+            uri = URI.create(configured);
+        } catch (IllegalArgumentException ex) {
+            uri = URI.create("http://localhost:8080");
+        }
+        String scheme = uri.getScheme() == null ? "http" : uri.getScheme();
+        String authority = uri.getRawAuthority() == null ? "localhost:8080" : uri.getRawAuthority();
+        this.origin = scheme + "://" + authority;
+        this.configuredPath = normalizePath(uri.getPath());
     }
 
     public String build(String code, HttpServletRequest request) {
-        String base = configuredBaseUrl == null || configuredBaseUrl.isBlank()
-                ? request.getScheme() + "://" + request.getServerName()
-                    + port(request) + request.getContextPath()
-                : configuredBaseUrl.replaceAll("/$", "");
-        return base + "/" + code;
+        if (legacyRequestOrigin && request != null) {
+            String scheme = request.getScheme();
+            String host = request.getServerName();
+            int port = request.getServerPort();
+            String path = configuredPath;
+            if (path.isEmpty()) {
+                path = normalizePath(request.getContextPath());
+            }
+            return scheme + "://" + host + portSuffix(scheme, port) + path + "/" + code;
+        }
+        return origin + configuredPath + "/" + code;
     }
 
-    private String port(HttpServletRequest request) {
-        int port = request.getServerPort();
-        return (request.isSecure() && port == 443) || (!request.isSecure() && port == 80)
-                ? "" : ":" + port;
+    private String portSuffix(String scheme, int port) {
+        boolean defaultPort = ("http".equalsIgnoreCase(scheme) && port == 80)
+                || ("https".equalsIgnoreCase(scheme) && port == 443);
+        return port <= 0 || defaultPort ? "" : ":" + port;
+    }
+
+    private String normalizePath(String path) {
+        if (path == null || path.isBlank() || path.equals("/")) {
+            return "";
+        }
+        return "/" + path.replaceAll("^/+|/+$", "");
+    }
+
+    private String stripTrailingSlashes(String value) {
+        String stripped = value.replaceAll("/+\\z", "");
+        return stripped.isEmpty() ? value : stripped;
     }
 }
--- a/src/main/java/com/example/shortener/service/LinkAnalyticsService.java
+++ b/src/main/java/com/example/shortener/service/LinkAnalyticsService.java
@@ -0,0 +1,97 @@
+package com.example.shortener.service;
+
+import com.example.shortener.api.AnalyticsResponse;
+import com.example.shortener.api.DailyClickCount;
+import com.example.shortener.api.ReferrerClickCount;
+import com.example.shortener.domain.Link;
+import com.example.shortener.exception.LinkNotFoundException;
+import com.example.shortener.repository.LinkRepository;
+import com.example.shortener.repository.RedirectEventRepository;
+import jakarta.servlet.http.HttpServletRequest;
+import java.time.Instant;
+import java.time.LocalDate;
+import java.time.ZoneId;
+import java.util.ArrayList;
+import java.util.HashMap;
+import java.util.List;
+import java.util.Map;
+import java.util.regex.Pattern;
+import org.slf4j.Logger;
+import org.slf4j.LoggerFactory;
+import org.springframework.beans.factory.annotation.Value;
+import org.springframework.data.domain.PageRequest;
+import org.springframework.stereotype.Service;
+import org.springframework.transaction.annotation.Transactional;
+
+@Service
+public class LinkAnalyticsService {
+    private static final Logger log = LoggerFactory.getLogger(LinkAnalyticsService.class);
+    private static final Pattern VALID_CODE = Pattern.compile("[A-Za-z0-9_-]{7}");
+
+    private final LinkRepository links;
+    private final RedirectEventRepository events;
+    private final AuditService audit;
+    private final ZoneId zone;
+
+    public LinkAnalyticsService(LinkRepository links, RedirectEventRepository events,
+                                AuditService audit,
+                                @Value("${app.time-zone:UTC}") String timeZone) {
+        this.links = links;
+        this.events = events;
+        this.audit = audit;
+        this.zone = ZoneId.of(timeZone == null || timeZone.isBlank() ? "UTC" : timeZone);
+    }
+
+    @Transactional
+    public AnalyticsResponse analytics(String code, HttpServletRequest request) {
+        try {
+            if (code == null || !VALID_CODE.matcher(code).matches()) {
+                throw missing(code, request);
+            }
+
+            Link link = links.findByCode(code).orElseThrow(() -> missing(code, request));
+            LocalDate today = Instant.now().atZone(zone).toLocalDate();
+            LocalDate start = today.minusDays(29);
+
+            Map<LocalDate, Long> byDay = new HashMap<>();
+            events.countByDay(link.getId(), start, today)
+                    .forEach(row -> byDay.put(row.getDay(), row.getCount()));
+
+            List<DailyClickCount> days = new ArrayList<>(30);
+            for (LocalDate day = start; !day.isAfter(today); day = day.plusDays(1)) {
+                days.add(new DailyClickCount(day, byDay.getOrDefault(day, 0L)));
+            }
+
+            List<ReferrerClickCount> referrers = events.topReferrers(link.getId(), PageRequest.of(0, 5))
+                    .stream()
+                    .map(row -> new ReferrerClickCount(row.getReferrer(), row.getCount()))
+                    .toList();
+            AnalyticsResponse response = new AnalyticsResponse(
+                    events.countByLinkId(link.getId()), days, referrers);
+            audit.record("ANALYTICS_REQUEST", safeCode(code), remoteAddress(request), "SUCCESS");
+            log.info("ANALYTICS_REQUEST code={} outcome=SUCCESS", safeCode(code));
+            return response;
+        } catch (LinkNotFoundException ex) {
+            throw ex;
+        } catch (RuntimeException ex) {
+            audit.recordFailure("ANALYTICS_REQUEST", safeCode(code), remoteAddress(request), "ERROR");
+            log.error("ANALYTICS_REQUEST code={} outcome=ERROR type={}",
+                    safeCode(code), ex.getClass().getSimpleName());
+            throw ex;
+        }
+    }
+
+    private LinkNotFoundException missing(String code, HttpServletRequest request) {
+        audit.recordFailure("ANALYTICS_REQUEST", safeCode(code), remoteAddress(request), "NOT_FOUND");
+        log.warn("ANALYTICS_REQUEST code={} outcome=NOT_FOUND", safeCode(code));
+        return new LinkNotFoundException();
+    }
+
+    private String safeCode(String code) {
+        return code == null ? null : code.substring(0, Math.min(7, code.length()));
+    }
+
+    private String remoteAddress(HttpServletRequest request) {
+        return request == null ? null : request.getRemoteAddr();
+    }
+}
--- a/src/main/java/com/example/shortener/controller/LinkController.java
+++ b/src/main/java/com/example/shortener/controller/LinkController.java
@@ -1,10 +1,13 @@
 package com.example.shortener.controller;
 
+import com.example.shortener.api.AnalyticsResponse;
 import com.example.shortener.api.CreateLinkRequest;
 import com.example.shortener.api.LinkResponse;
+import com.example.shortener.service.LinkAnalyticsService;
 import com.example.shortener.service.LinkService;
 import jakarta.servlet.http.HttpServletRequest;
 import jakarta.validation.Valid;
+import java.util.regex.Pattern;
 import org.slf4j.Logger;
 import org.slf4j.LoggerFactory;
 import org.springframework.http.ResponseEntity;
@@ -19,22 +22,35 @@
 @RequestMapping("/api/links")
 public class LinkController {
     private static final Logger log = LoggerFactory.getLogger(LinkController.class);
-    private final LinkService service;
+    private static final Pattern VALID_CODE = Pattern.compile("[A-Za-z0-9_-]{7}");
 
-    public LinkController(LinkService service) {
+    private final LinkService service;
+    private final LinkAnalyticsService analytics;
+
+    public LinkController(LinkService service, LinkAnalyticsService analytics) {
         this.service = service;
+        this.analytics = analytics;
     }
 
     @PostMapping
-    public ResponseEntity<LinkResponse> create(@Valid @RequestBody CreateLinkRequest request,
-                                                HttpServletRequest servletRequest) {
+    public ResponseEntity<LinkResponse> create(@Valid @RequestBody CreateLinkRequest request, HttpServletRequest servletRequest) {
         log.info("Processing link creation request");
         return ResponseEntity.status(201).body(service.create(request.url(), servletRequest));
     }
 
+    @GetMapping("/{code}/analytics")
+    public AnalyticsResponse analytics(@PathVariable String code, HttpServletRequest request) {
+        log.info("ANALYTICS_REQUEST code={}", safeAnalyticsCode(code));
+        return analytics.analytics(code, request);
+    }
+
     @GetMapping("/{code}")
     public LinkResponse details(@PathVariable String code, HttpServletRequest request) {
         log.info("Processing link details request");
         return service.details(code, request);
     }
+
+    private String safeAnalyticsCode(String code) {
+        return code != null && VALID_CODE.matcher(code).matches() ? code : "<invalid>";
+    }
 }
--- a/src/main/java/com/example/shortener/service/LinkService.java
+++ b/src/main/java/com/example/shortener/service/LinkService.java
@@ -2,12 +2,20 @@
 
 import com.example.shortener.api.LinkResponse;
 import com.example.shortener.domain.Link;
+import com.example.shortener.domain.RedirectEvent;
 import com.example.shortener.exception.CodeGenerationException;
+import com.example.shortener.exception.InvalidUrlException;
 import com.example.shortener.exception.LinkNotFoundException;
 import com.example.shortener.repository.LinkRepository;
+import com.example.shortener.repository.RedirectEventRepository;
 import jakarta.servlet.http.HttpServletRequest;
+import java.time.Instant;
+import java.time.ZoneId;
+import java.util.regex.Pattern;
 import org.slf4j.Logger;
 import org.slf4j.LoggerFactory;
+import org.springframework.beans.factory.annotation.Autowired;
+import org.springframework.beans.factory.annotation.Value;
 import org.springframework.dao.DataIntegrityViolationException;
 import org.springframework.stereotype.Service;
 import org.springframework.transaction.PlatformTransactionManager;
@@ -15,103 +23,116 @@
 import org.springframework.transaction.annotation.Transactional;
 import org.springframework.transaction.support.TransactionTemplate;
 
-import java.time.Instant;
-import java.util.regex.Pattern;
-
 @Service
 public class LinkService {
     private static final Logger log = LoggerFactory.getLogger(LinkService.class);
     private static final int MAX_ATTEMPTS = 10;
-    private static final int CODE_LENGTH = 7;
+    private static final int MAX_REFERRER_LENGTH = 2048;
     private static final Pattern VALID_CODE = Pattern.compile("[A-Za-z0-9_-]{7}");
+    private static final String CREATION_AUDITED = "shortener.creation.audited";
 
     private final LinkRepository links;
     private final AuditService audit;
     private final UrlValidator validator;
     private final ShortCodeGenerator generator;
     private final ShortUrlBuilder urlBuilder;
+    private final RedirectEventRepository events;
+    private final ZoneId zone;
     private final TransactionTemplate creationTransaction;
 
+    @Autowired
     public LinkService(LinkRepository links, AuditService audit, UrlValidator validator,
                        ShortCodeGenerator generator, ShortUrlBuilder urlBuilder,
-                       PlatformTransactionManager transactionManager) {
+                       RedirectEventRepository events, PlatformTransactionManager manager,
+                       @Value("${app.time-zone:UTC}") String timeZone) {
         this.links = links;
         this.audit = audit;
         this.validator = validator;
         this.generator = generator;
         this.urlBuilder = urlBuilder;
-        this.creationTransaction = new TransactionTemplate(transactionManager);
+        this.events = events;
+        this.zone = configuredZone(timeZone);
+        this.creationTransaction = new TransactionTemplate(manager);
         this.creationTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
     }
 
+    public LinkService(LinkRepository links, AuditService audit, UrlValidator validator,
+                       ShortCodeGenerator generator, ShortUrlBuilder urlBuilder,
+                       PlatformTransactionManager manager) {
+        this(links, audit, validator, generator, urlBuilder, null, manager, "UTC");
+    }
+
     public LinkResponse create(String originalUrl, HttpServletRequest request) {
-        validator.validate(originalUrl);
-        Link link = null;
+        try {
+            validator.validate(originalUrl);
+        } catch (InvalidUrlException ex) {
+            audit.recordFailure("CREATION_REJECTED", null, remoteAddress(request), ex.getReason());
+            if (request != null) {
+                request.setAttribute(CREATION_AUDITED, Boolean.TRUE);
+            }
+            log.warn("CREATION_REJECTED outcome={}", ex.getReason());
+            throw ex;
+        }
+
         for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
             String code = generator.generate();
             try {
-                Link candidate = creationTransaction.execute(status -> {
+                Link link = creationTransaction.execute(status -> {
                     Link saved = links.saveAndFlush(new Link(code, originalUrl, Instant.now()));
-                    audit.record("LINK_CREATED", saved.getCode(), request.getRemoteAddr(), "SUCCESS");
+                    audit.record("LINK_CREATED", saved.getCode(), remoteAddress(request), "SUCCESS");
                     return saved;
                 });
-                if (candidate != null) {
-                    link = candidate;
-                    break;
+                if (link != null) {
+                    log.info("LINK_CREATED code={} outcome=SUCCESS", link.getCode());
+                    return response(link, request);
                 }
             } catch (DataIntegrityViolationException ex) {
                 log.warn("Code collision during LINK_CREATED attempt={}", attempt + 1);
             }
         }
-        if (link == null) {
-            log.error("Short-code generation exhausted retry limit");
-            throw new CodeGenerationException("Unable to create a short link");
-        }
-        log.info("LINK_CREATED code={} outcome=SUCCESS", link.getCode());
-        return response(link, request);
-    }
-
-    private void validateCodeAndAudit(String code, HttpServletRequest request) {
-        if (!isValidCode(code)) {
-            audit.recordFailure("UNKNOWN_CODE", auditCode(code), request.getRemoteAddr(), "NOT_FOUND");
-            log.warn("UNKNOWN_CODE outcome=NOT_FOUND invalid_code=true");
-            throw new LinkNotFoundException();
-        }
-    }
-
-    private boolean isValidCode(String code) {
-        return code != null && VALID_CODE.matcher(code).matches();
-    }
-
-    private String auditCode(String code) {
-        if (code == null) {
-            return null;
-        }
-        return code.substring(0, Math.min(CODE_LENGTH, code.length()));
-    }
-
-    @Transactional
-    public LinkResponse details(String code, HttpServletRequest request) {
-        validateCodeAndAudit(code, request);
-        Link link = links.findByCode(code).orElseThrow(() -> missing(code, request));
-        return response(link, request);
+        log.error("Short-code generation exhausted retry limit");
+        throw new CodeGenerationException("Unable to create a short link");
     }
 
     @Transactional
     public String resolve(String code, HttpServletRequest request) {
-        validateCodeAndAudit(code, request);
+        validateCode(code, request);
         if (links.incrementClickCount(code) != 1) {
             throw missing(code, request);
         }
         Link link = links.findByCode(code).orElseThrow(() -> missing(code, request));
-        audit.record("REDIRECTED", code, request.getRemoteAddr(), "REDIRECTED");
+        if (events != null) {
+            String referrer = request == null ? null : request.getHeader("Referer");
+            if (referrer != null) {
+                referrer = referrer.substring(0, Math.min(MAX_REFERRER_LENGTH, referrer.length()));
+                if (referrer.isBlank()) {
+                    referrer = null;
+                }
+            }
+            Instant now = Instant.now();
+            events.save(new RedirectEvent(link, now, now.atZone(zone).toLocalDate(), referrer));
+        }
+        audit.record("REDIRECTED", code, remoteAddress(request), "REDIRECTED");
         log.info("REDIRECTED code={} outcome=REDIRECTED", code);
         return link.getOriginalUrl();
     }
 
+    @Transactional
+    public LinkResponse details(String code, HttpServletRequest request) {
+        validateCode(code, request);
+        return response(links.findByCode(code).orElseThrow(() -> missing(code, request)), request);
+    }
+
+    private void validateCode(String code, HttpServletRequest request) {
+        if (code == null || !VALID_CODE.matcher(code).matches()) {
+            throw missing(code, request);
+        }
+    }
+
     private LinkNotFoundException missing(String code, HttpServletRequest request) {
-        audit.recordFailure("UNKNOWN_CODE", code, request.getRemoteAddr(), "NOT_FOUND");
-        log.warn("UNKNOWN_CODE code={} outcome=NOT_FOUND", code);
+        String safeCode = code == null ? null : code.substring(0, Math.min(7, code.length()));
+        audit.recordFailure("UNKNOWN_CODE", safeCode, remoteAddress(request), "NOT_FOUND");
+        log.warn("UNKNOWN_CODE code={} outcome=NOT_FOUND", safeCode);
         return new LinkNotFoundException();
     }
 
@@ -119,4 +140,12 @@
         return new LinkResponse(link.getCode(), urlBuilder.build(link.getCode(), request),
                 link.getOriginalUrl(), link.getClickCount());
     }
+
+    private String remoteAddress(HttpServletRequest request) {
+        return request == null ? null : request.getRemoteAddr();
+    }
+
+    private ZoneId configuredZone(String timeZone) {
+        return ZoneId.of(timeZone == null || timeZone.isBlank() ? "UTC" : timeZone);
+    }
 }
--- a/src/main/java/com/example/shortener/controller/GlobalExceptionHandler.java
+++ b/src/main/java/com/example/shortener/controller/GlobalExceptionHandler.java
@@ -19,6 +19,8 @@
 @RestControllerAdvice
 public class GlobalExceptionHandler {
     private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
+    private static final String CREATION_AUDITED = "shortener.creation.audited";
+
     private final AuditService audit;
 
     public GlobalExceptionHandler(AuditService audit) {
@@ -27,8 +29,12 @@
 
     @ExceptionHandler(InvalidUrlException.class)
     public ResponseEntity<ProblemDetail> invalid(InvalidUrlException ex, HttpServletRequest request) {
-        reject(request, "INVALID_URL:" + ex.getMessage());
-        log.warn("CREATION_REJECTED outcome=INVALID_URL");
+        if (isLinkCreationRequest(request) && !alreadyAudited(request)) {
+            audit.recordFailure("CREATION_REJECTED", null, remoteAddress(request),
+                    "INVALID_URL:" + ex.getMessage());
+            markAudited(request);
+        }
+        log.warn("CREATION_REJECTED outcome={}", ex.getReason());
         return problem(HttpStatus.BAD_REQUEST, ex.getMessage());
     }
 
@@ -37,7 +43,11 @@
     public ResponseEntity<ProblemDetail> badRequest(Exception ex, HttpServletRequest request) {
         String detail = ex instanceof MethodArgumentNotValidException
                 ? "url must not be blank" : "request body is invalid";
-        reject(request, "INVALID_REQUEST:" + detail);
+        if (isLinkCreationRequest(request) && !alreadyAudited(request)) {
+            audit.recordFailure("CREATION_REJECTED", null, remoteAddress(request),
+                    "INVALID_REQUEST:" + detail);
+            markAudited(request);
+        }
         log.warn("CREATION_REJECTED outcome=INVALID_REQUEST");
         return problem(HttpStatus.BAD_REQUEST, detail);
     }
@@ -50,6 +60,7 @@
 
     @ExceptionHandler(LinkNotFoundException.class)
     public ResponseEntity<ProblemDetail> notFound(LinkNotFoundException ex) {
+        log.warn("SHORT_LINK_NOT_FOUND outcome=NOT_FOUND");
         return problem(HttpStatus.NOT_FOUND, ex.getMessage());
     }
 
@@ -59,11 +70,26 @@
         return problem(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected server error occurred");
     }
 
-    private void reject(HttpServletRequest request, String outcome) {
-        if (request.getRequestURI().equals("/api/links")
-                || request.getRequestURI().startsWith("/api/links/")) {
-            audit.recordFailure("CREATION_REJECTED", null, request.getRemoteAddr(), outcome);
+    private boolean isLinkCreationRequest(HttpServletRequest request) {
+        if (request == null) {
+            return false;
         }
+        String uri = request.getRequestURI();
+        return uri != null && (uri.equals("/api/links") || uri.equals("/api/links/"));
+    }
+
+    private boolean alreadyAudited(HttpServletRequest request) {
+        return request != null && Boolean.TRUE.equals(request.getAttribute(CREATION_AUDITED));
+    }
+
+    private void markAudited(HttpServletRequest request) {
+        if (request != null) {
+            request.setAttribute(CREATION_AUDITED, Boolean.TRUE);
+        }
+    }
+
+    private String remoteAddress(HttpServletRequest request) {
+        return request == null ? null : request.getRemoteAddr();
     }
 
     private ResponseEntity<ProblemDetail> problem(HttpStatus status, String detail) {
--- a/src/main/resources/application.yml
+++ b/src/main/resources/application.yml
@@ -7,3 +7,10 @@
     hibernate:
       ddl-auto: update
     open-in-view: false
+
+app:
+  public-origin: ${APP_PUBLIC_ORIGIN:http://localhost:${server.port:8080}}
+  time-zone: ${APP_TIME_ZONE:UTC}
+
+server:
+  port: 8080
```

