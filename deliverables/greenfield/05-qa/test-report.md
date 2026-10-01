# Test report

Fresh `./gradlew clean test` of the released code, run by the exporter (build succeeded in 7s). 29 tests: 29 passed, 0 failed.

## By test class

| Test class | Tests | Passed | Failed |
|---|---|---|---|
| UrlShortenerApplicationTests | 1 | 1 | 0 |
| UrlShortenerHttpIntegrationTest | 8 | 8 | 0 |
| GlobalExceptionHandlerTest | 7 | 7 | 0 |
| AuditServiceTest | 2 | 2 | 0 |
| LinkServiceTest | 7 | 7 | 0 |
| ShortCodeAndUrlBuilderTest | 2 | 2 | 0 |
| UrlValidatorTest | 2 | 2 | 0 |

## All test cases

| Class | Test | Result | Time |
|---|---|---|---|
| UrlShortenerApplicationTests | contextLoads() | PASSED | 0.26s |
| UrlShortenerHttpIntegrationTest | concurrentRedirectsDoNotLoseClickIncrements() | PASSED | 0.33s |
| UrlShortenerHttpIntegrationTest | unknownCodesReturnProblemDetailsWithoutLocationHeadersAndPersistAudits() | PASSED | 0.04s |
| UrlShortenerHttpIntegrationTest | invalidShortCodeReturns404WithoutARedirectLocationAndPersistsUnknownAudit() | PASSED | 0.01s |
| UrlShortenerHttpIntegrationTest | redirectsCountsClicksAndReturnsDetails() | PASSED | 0.01s |
| UrlShortenerHttpIntegrationTest | rejectsInvalidRequestsAndPersistsRejectionAuditsWithoutCreatingLinks() | PASSED | 0.02s |
| UrlShortenerHttpIntegrationTest | writesIdentifiableOperationalLogsForKeyOperations(CapturedOutput) | PASSED | 0.01s |
| UrlShortenerHttpIntegrationTest | rejectsOverlongDestinationWithValidationProblem() | PASSED | 0.00s |
| UrlShortenerHttpIntegrationTest | createsDuplicateLinksWithSafeCodesAndAudits() | PASSED | 0.01s |
| GlobalExceptionHandlerTest | handlesMalformedCreationRequestsAndOnlyAuditsCreationPaths() | PASSED | 0.25s |
| GlobalExceptionHandlerTest | logsUnexpectedRequestProcessingErrorsWithoutExposingTheExceptionMessage(CapturedOutput) | PASSED | 0.00s |
| GlobalExceptionHandlerTest | mapsBeanValidationFailuresToAReadableBadRequest() | PASSED | 0.11s |
| GlobalExceptionHandlerTest | returnsGenericFiveHundredProblemDetailForUnexpectedFailures(CapturedOutput) | PASSED | 0.00s |
| GlobalExceptionHandlerTest | mapsNotFoundAndProvidesOperationalErrorHandlingForAllKeyOutcomes() | PASSED | 0.00s |
| GlobalExceptionHandlerTest | mapsCodeGenerationExhaustionToServiceUnavailableWithoutInternalDetails() | PASSED | 0.00s |
| GlobalExceptionHandlerTest | mapsInvalidCreationAndUnexpectedErrorsWithoutSensitiveDetails() | PASSED | 0.00s |
| AuditServiceTest | propagatesAuditPersistenceFailureInsteadOfSwallowingIt() | PASSED | 0.06s |
| AuditServiceTest | substitutesUnknownIpWhenNoClientIpIsAvailable() | PASSED | 0.00s |
| LinkServiceTest | resolveHandlesIncrementThenLookupRaceAsMissing() | PASSED | 0.15s |
| LinkServiceTest | retriesCollisionThenCreatesAndAudits() | PASSED | 0.01s |
| LinkServiceTest | rejectsExhaustedCollisions() | PASSED | 0.02s |
| LinkServiceTest | detailsAndResolveCoverSuccessAndMissingBranches() | PASSED | 0.00s |
| LinkServiceTest | rejectsInvalidCodeBeforeRepositoryAccess() | PASSED | 0.00s |
| LinkServiceTest | malformedDetailsCodeIsAuditedWithASevenCharacterCode() | PASSED | 0.00s |
| LinkServiceTest | nullDetailsCodeIsAuditedWithoutRepositoryAccess() | PASSED | 0.00s |
| ShortCodeAndUrlBuilderTest | generatesSevenUrlSafeCharacters() | PASSED | 0.01s |
| ShortCodeAndUrlBuilderTest | buildsConfiguredBaseAndDerivedBasesWithPortRules() | PASSED | 0.00s |
| UrlValidatorTest | rejectsNullBlankRelativeUnsupportedMalformedAndOverlongUrls() | PASSED | 0.00s |
| UrlValidatorTest | acceptsAbsoluteHttpAndHttpsUrls() | PASSED | 0.00s |

