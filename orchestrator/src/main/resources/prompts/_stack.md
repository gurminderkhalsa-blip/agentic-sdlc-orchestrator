# Technology stack of the target service (url-shortener)
- Java 21, Spring Boot 4.1, Gradle (wrapper already present), H2 in-memory database, Spring Data JPA, Bean Validation.
- Base package: com.example.shortener (the application class com.example.shortener.UrlShortenerApplication already exists).
- Use jakarta.* imports (jakarta.persistence, jakarta.validation), never javax.*.
- Spring Boot 4 uses Jackson 3: packages are tools.jackson.* (e.g. tools.jackson.databind.ObjectMapper); annotations stay com.fasterxml.jackson.annotation.*. Prefer Java records for request/response bodies so no Jackson imports are needed.
- Tests: JUnit 5, AssertJ, Mockito. For HTTP tests use @SpringBootTest + @AutoConfigureMockMvc, where AutoConfigureMockMvc is org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc, and MockMvc request builders from org.springframework.test.web.servlet.request.MockMvcRequestBuilders. Send JSON bodies as text blocks. Do not use @DataJpaTest, TestRestTemplate or WebTestClient (not on the classpath).
- @MockitoBean is org.springframework.test.context.bean.override.mockito.MockitoBean (@MockBean no longer exists).
- Available dependencies: spring-boot-starter-webmvc, -data-jpa, -validation, h2, spring-boot-starter-test, spring-boot-starter-webmvc-test. Adding a dependency requires editing build.gradle, which needs human approval: avoid it unless truly necessary.
- Java pitfalls to avoid: a text block must start with """ followed by a line break (never """{...}""" on one line; use a normal string for one-line JSON); method names cannot contain spaces; declare `throws Exception` on test methods and test helper methods that call MockMvc or read responses.
- Keep layers clean: controller -> service -> repository. Constructor injection only. Validate input at the controller boundary and return RFC 7807 ProblemDetail for errors.
