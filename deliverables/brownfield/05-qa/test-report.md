# Test report

Fresh `./gradlew clean test` of the released code, run by the exporter (build succeeded in 8s). 40 tests: 40 passed, 0 failed.

## By test class

| Test class | Tests | Passed | Failed |
|---|---|---|---|
| AnalyticsAndValidationIntegrationTest | 6 | 6 | 0 |
| UrlShortenerApplicationTests | 1 | 1 | 0 |
| UrlShortenerHttpIntegrationTest | 9 | 9 | 0 |
| GlobalExceptionHandlerTest | 7 | 7 | 0 |
| AuditServiceTest | 2 | 2 | 0 |
| LinkAnalyticsServiceTest | 4 | 4 | 0 |
| LinkServiceTest | 7 | 7 | 0 |
| ShortCodeAndUrlBuilderTest | 2 | 2 | 0 |
| UrlValidatorTest | 2 | 2 | 0 |

## All test cases

| Class | Test | Result | Time |
|---|---|---|---|
| AnalyticsAndValidationIntegrationTest | rejectsConfiguredSelfLinksRegardlessOfIncomingHostAndAuditsRejection() | PASSED | 0.55s |
| AnalyticsAndValidationIntegrationTest | redirectPersistsTimestampAndRefererAndAnalyticsUsesThirtyDaysAndTopFive() | PASSED | 0.14s |
| AnalyticsAndValidationIntegrationTest | analyticsRequestIsOperationallyLoggedWithoutDestinationDisclosure(CapturedOutput) | PASSED | 0.01s |
| AnalyticsAndValidationIntegrationTest | analyticsNotFoundReturnsExistingProblemAndWritesAudit() | PASSED | 0.01s |
| AnalyticsAndValidationIntegrationTest | rejectsOverlongDestinationBeforePersistenceAndAuditsLengthReason() | PASSED | 0.01s |
| AnalyticsAndValidationIntegrationTest | oversizedRefererIsTruncatedBeforeRedirectEventPersistence() | PASSED | 0.01s |
| UrlShortenerApplicationTests | contextLoads() | PASSED | 0.00s |
| UrlShortenerHttpIntegrationTest | concurrentRedirectsDoNotLoseClickIncrements() | PASSED | 0.03s |
| UrlShortenerHttpIntegrationTest | unknownCodesReturnProblemDetailsWithoutLocationHeadersAndPersistAudits() | PASSED | 0.01s |
| UrlShortenerHttpIntegrationTest | invalidShortCodeReturns404WithoutARedirectLocationAndPersistsUnknownAudit() | PASSED | 0.01s |
| UrlShortenerHttpIntegrationTest | redirectsCountsClicksAndReturnsDetails() | PASSED | 0.02s |
| UrlShortenerHttpIntegrationTest | rejectsInvalidRequestsAndPersistsRejectionAuditsWithoutCreatingLinks() | PASSED | 0.02s |
| UrlShortenerHttpIntegrationTest | writesIdentifiableOperationalLogsForKeyOperations(CapturedOutput) | PASSED | 0.01s |
| UrlShortenerHttpIntegrationTest | rejectsOverlongDestinationWithValidationProblem() | PASSED | 0.00s |
| UrlShortenerHttpIntegrationTest | createsDuplicateLinksWithSafeCodesAndAudits() | PASSED | 0.01s |
| UrlShortenerHttpIntegrationTest | invalidAnalyticsCodeIsValidatedBeforeLoggingThePathVariable(CapturedOutput) | PASSED | 0.00s |
| GlobalExceptionHandlerTest | handlesMalformedCreationRequestsAndOnlyAuditsCreationPaths() | PASSED | 0.28s |
| GlobalExceptionHandlerTest | logsUnexpectedRequestProcessingErrorsWithoutExposingTheExceptionMessage(CapturedOutput) | PASSED | 0.00s |
| GlobalExceptionHandlerTest | mapsBeanValidationFailuresToAReadableBadRequest() | PASSED | 0.13s |
| GlobalExceptionHandlerTest | returnsGenericFiveHundredProblemDetailForUnexpectedFailures(CapturedOutput) | PASSED | 0.00s |
| GlobalExceptionHandlerTest | mapsNotFoundAndProvidesOperationalErrorHandlingForAllKeyOutcomes() | PASSED | 0.00s |
| GlobalExceptionHandlerTest | mapsCodeGenerationExhaustionToServiceUnavailableWithoutInternalDetails() | PASSED | 0.00s |
| GlobalExceptionHandlerTest | mapsInvalidCreationAndUnexpectedErrorsWithoutSensitiveDetails() | PASSED | 0.00s |
| AuditServiceTest | propagatesAuditPersistenceFailureInsteadOfSwallowingIt() | PASSED | 0.07s |
| AuditServiceTest | substitutesUnknownIpWhenNoClientIpIsAvailable() | PASSED | 0.00s |
| LinkAnalyticsServiceTest | invalidAnalyticsCodesAreAuditedAsNotFound() | PASSED | 0.09s |
| LinkAnalyticsServiceTest | auditsAndPropagatesDatabaseErrors() | PASSED | 0.03s |
| LinkAnalyticsServiceTest | aggregatesDatabaseProjectionsIntoThirtyBucketsAndFiveReferrers() | PASSED | 0.05s |
| LinkAnalyticsServiceTest | auditsAnalyticsNotFoundWithoutRunningEventQueries() | PASSED | 0.00s |
| LinkServiceTest | resolveHandlesIncrementThenLookupRaceAsMissing() | PASSED | 0.12s |
| LinkServiceTest | retriesCollisionThenCreatesAndAudits() | PASSED | 0.01s |
| LinkServiceTest | rejectsExhaustedCollisions() | PASSED | 0.01s |
| LinkServiceTest | detailsAndResolveCoverSuccessAndMissingBranches() | PASSED | 0.00s |
| LinkServiceTest | rejectsInvalidCodeBeforeRepositoryAccess() | PASSED | 0.00s |
| LinkServiceTest | malformedDetailsCodeIsAuditedWithASevenCharacterCode() | PASSED | 0.00s |
| LinkServiceTest | nullDetailsCodeIsAuditedWithoutRepositoryAccess() | PASSED | 0.00s |
| ShortCodeAndUrlBuilderTest | generatesSevenUrlSafeCharacters() | PASSED | 0.00s |
| ShortCodeAndUrlBuilderTest | buildsConfiguredBaseAndDerivedBasesWithPortRules() | PASSED | 0.00s |
| UrlValidatorTest | rejectsNullBlankRelativeUnsupportedMalformedAndOverlongUrls() | PASSED | 0.00s |
| UrlValidatorTest | acceptsAbsoluteHttpAndHttpsUrls() | PASSED | 0.00s |

