import { existsSync, readFileSync } from 'node:fs';
import nodePath from 'node:path';

const provider = (file, ...tests) => ({
  kind: 'provider',
  tests: tests.map(test => ({ file, test })),
});

const scopeBlock = (reason, file, ...tests) => ({
  kind: 'scope-block',
  tests: tests.map(test => ({ file, test })),
  reason,
});

const live = (testMapping, scenarioIds = [], unresolvedReason = null) => ({
  status: 'live',
  testMapping,
  scenarioIds,
  unresolvedReason,
});

const nonExposure = (testMapping, scenarioIds = []) => ({
  status: 'non-exposure',
  testMapping,
  scenarioIds,
  unresolvedReason: testMapping.reason,
});

const web = 'src/test/java/dev/espero/festival/e2e/ReleaseReadinessHttpE2eTest.java';
const account = 'src/test/java/dev/espero/festival/e2e/OperationalAccountPropagationE2eTest.java';
const crowdingE2e = 'src/test/java/dev/espero/festival/e2e/CrowdingConcurrencyE2eTest.java';
const publication = 'src/test/java/dev/espero/festival/e2e/CatalogPublicationLifecycleE2eTest.java';
const sessionE2e = 'src/test/java/dev/espero/festival/e2e/AdminSessionReleaseE2eTest.java';
const failure = 'src/test/java/dev/espero/festival/e2e/ReleaseFailureModesHttpE2eTest.java';
const operator = 'src/test/java/dev/espero/festival/e2e/OperatorToolProcessE2eTest.java';
const releaseGate = 'src/test/java/dev/espero/festival/e2e/OperationalReleaseGateE2eTest.java';
const emptyPreflight = 'src/test/java/dev/espero/festival/e2e/DatabasePreflightEmptyDatabaseE2eTest.java';

const scenarioTests = {
  'HTTP-01': [{ file: web, test: 'candidatePublishesBeforeStartupAndPassesVisitorAndOperatorJourneys' }],
  'HTTP-02': [{ file: web, test: 'candidatePublishesBeforeStartupAndPassesVisitorAndOperatorJourneys' }],
  'HTTP-03': [{ file: web, test: 'candidatePublishesBeforeStartupAndPassesVisitorAndOperatorJourneys' }],
  'HTTP-04': [{ file: web, test: 'candidatePublishesBeforeStartupAndPassesVisitorAndOperatorJourneys' }],
  'HTTP-05': [{ file: web, test: 'candidatePublishesBeforeStartupAndPassesVisitorAndOperatorJourneys' }],
  'HTTP-06': [{ file: web, test: 'candidatePublishesBeforeStartupAndPassesVisitorAndOperatorJourneys' }],
  'HTTP-07': [{ file: sessionE2e, test: 'userSessionLifecycleRotatesAndRevokesRefreshCredentials' }],
  'HTTP-08': [{ file: crowdingE2e, test: 'concurrentIdenticalCrowdingWritesMutateOnceReplaySafelyAndRejectKeyReuse' }],
  'HTTP-09': [{ file: crowdingE2e, test: 'loopbackAdminRequiresIfMatchAndRejectsStaleRepresentation' }],
  'HTTP-10': [{ file: web, test: 'publicQueriesRejectUnpublishedLocalesAndDuplicateParametersWithReleaseMetadata' }],
  'HTTP-11': [{ file: web, test: 'publicConditionalReadsAcceptWeakAndMultiValueValidators' }],
  'HTTP-12': [{ file: sessionE2e, test: 'sessionBoundaryRejectsBadOriginCredentialsTokensAndMalformedBodies' }],
  'HTTP-13': [{ file: web, test: 'releaseCliChildCannotBeRedirectedByAHostileDatasourceOverride' }],
  'HTTP-14': [{ file: web, test: 'traversesEveryDeclaredDateCategoryAndExposedSpaceFilter' }],
  'HTTP-15': [{ file: account, test: 'cliSetAndClearPropagateToHttpWithConditionalEtagSemantics' }],
  'HTTP-16': [{ file: crowdingE2e, test: 'concurrentIdenticalCrowdingWritesMutateOnceReplaySafelyAndRejectKeyReuse' }],
  'HTTP-17': [{ file: publication, test: 'publicationAndRollbackBecomeVisibleAfterRestartWithoutChangingDynamicState' }],
  'HTTP-18': [{ file: web, test: 'publicInputFailuresStaySafeAndDoNotBlockAnonymousCatalogNavigation' }],
  'HTTP-19': [{ file: web, test: 'defaultDateAndLineupStayAlignedBeforeAndAfterTheFestivalCalendar' }],
  'HTTP-20': [{ file: failure, test: 'unpublishedCatalogStaysUnavailableWhileRateLimitsAreScopedAndRecoverable' }],
  'HTTP-21': [{ file: failure, test: 'unpublishedCatalogStaysUnavailableWhileRateLimitsAreScopedAndRecoverable' }],
  'HTTP-22': [{ file: failure, test: 'unpublishedCatalogStaysUnavailableWhileRateLimitsAreScopedAndRecoverable' }],
  'HTTP-23': [{ file: account, test: 'transferWindowBoundariesChangeExposureAndConditionalRepresentationAtExactSeconds' }],
  'HTTP-24': [{ file: crowdingE2e, test: 'loopbackAdminRejectsGapDayWritesButAllowsFestivalDayWritesOutsideHours' }],
  'OPS-01': [{ file: operator, test: 'catalogCliUsesBaselineGuardsAndRollsBackThroughItsRealMain' }],
  'OPS-02': [{ file: operator, test: 'catalogCliUsesBaselineGuardsAndRollsBackThroughItsRealMain' }],
  'OPS-03': [{ file: operator, test: 'catalogCliRejectsCorruptDraftAndRecoversWithAValidReplacementThroughSeparateProcesses' }],
  'OPS-04': [{ file: operator, test: 'accountSettingsCliKeepsDryRunsSafeAndChangesVersionedHistoryThroughItsRealMain' }],
  'OPS-05': [{ file: releaseGate, test: 'readOnlyPreflightRoleInspectsPublishedDatabaseWithoutMutation' }],
  'OPS-06': [{ file: 'src/test/java/dev/espero/festival/workbench/CatalogWorkbenchIntegrationTest.java', test: 'validatesDiffsImportsPublishesAndChecksTheBackend' }],
  'OPS-07': [{ file: operator, test: 'catalogCliRejectsCorruptDraftAndRecoversWithAValidReplacementThroughSeparateProcesses' }],
  'OPS-08': [{ file: operator, test: 'catalogCliCannotImportWithAReadOnlyRoleAndDoesNotCreateDraftOrAuditRows' }],
  'OPS-09': [{ file: operator, test: 'accountSettingsCliRejectsUnknownInputAndStaleExpectedVersionWithoutWriting' }],
  'OPS-10': [{ file: releaseGate, test: 'readOnlyAccountRoleCannotClearOrRestoreAnExistingSetting' }],
  'OPS-11': [{ file: operator, test: 'databasePreflightStandaloneMainStopsOnExistingCatalogAndRedactsConfigurationFailures' }],
  'OPS-12': [{ file: operator, test: 'databasePreflightStandaloneMainStopsOnExistingCatalogAndRedactsConfigurationFailures' }],
  'OPS-13': [{ file: operator, test: 'catalogCliRollsBackLateAuditFailureAndCanRetry' }],
  'OPS-14': [{ file: operator, test: 'catalogCliRollsBackLateAuditFailureAndCanRetry' }],
  'OPS-15': [{ file: operator, test: 'catalogCliRollsBackLateAuditFailureAndCanRetry' }],
  'OPS-16': [{ file: operator, test: 'catalogCliSerializesCompetingProcessesWithoutLosingTheWinner' }],
  'OPS-17': [{ file: operator, test: 'catalogCliSerializesCompetingProcessesWithoutLosingTheWinner' }],
  'OPS-18': [{ file: emptyPreflight, test: 'standalonePreflightCanInspectAnEmptyDatabaseTwiceWithoutCreatingApplicationState' }],
  'OPS-19': [{ file: operator, test: 'catalogCliRoundTripsEveryManifestSectionIncludingHistoricalAssetsAndUnfilteredPins' }],
  'OPS-20': [{ file: operator, test: 'catalogCliBlocksEveryPartialLegacyTicketScheduleAndPublishesOnlyAfterCorrection' }],
};

const providerTests = {
  getConfig: provider('src/test/java/dev/espero/festival/web/CatalogControllerOpenApiTest.java', 'validatesTheConfigPayloadAgainstOpenApi'),
  getCrowding: provider('src/test/java/dev/espero/festival/web/CrowdingControllerOpenApiTest.java', 'validatesPublicSuccessAndConditionalNotModifiedResponses'),
  getNotices: provider('src/test/java/dev/espero/festival/web/NoticeFlowIntegrationTest.java', 'listsTodaysGeneralNoticeAndAnyDayLostFoundOrderedNewestFirst', 'resolvesContentLocaleWithFallbackAndKeepsTitleBodyLinkLabelInTheSameLanguage'),
  getGoods: provider('src/test/java/dev/espero/festival/web/GoodsFlowIntegrationTest.java', 'listsGoodsWithResolvedColorsAndSizes'),
  getGoodsAvailability: provider('src/test/java/dev/espero/festival/web/GoodsFlowIntegrationTest.java', 'returnsAvailabilityWithCombinationsAndAllSoldOut'),
  getGood: provider('src/test/java/dev/espero/festival/web/AdminGoodsProductCreationFlowIntegrationTest.java', 'createsOpenApiShapedOptionsProductAndExposesEveryReadModel'),
  getGoodAvailability: provider('src/test/java/dev/espero/festival/web/AdminGoodsProductCreationFlowIntegrationTest.java', 'createsOpenApiShapedOptionsProductAndExposesEveryReadModel'),
  getPaymentGuide: provider('src/test/java/dev/espero/festival/web/GoodsFlowIntegrationTest.java', 'returnsPaymentGuideAccountOnlyWhenConfigured'),
  getLineup: provider('src/test/java/dev/espero/festival/web/PerformanceControllerOpenApiTest.java', 'validatesLineupSuccessAndEmptyResponses'),
  getArtist: provider('src/test/java/dev/espero/festival/web/PerformanceControllerOpenApiTest.java', 'validatesArtistSuccessAndMissingOptionalResponses'),
  getTimetable: provider('src/test/java/dev/espero/festival/web/PerformanceControllerOpenApiTest.java', 'validatesTimetableNormalAndEmptyResponses'),
  getPerformance: provider('src/test/java/dev/espero/festival/web/PerformanceControllerOpenApiTest.java', 'validatesPerformanceNormalNullableEmptyAndErrorResponses'),
  getProhibitedItems: provider('src/test/java/dev/espero/festival/web/PerformanceControllerOpenApiTest.java', 'validatesProhibitedNormalEmptyAndBadRequestResponses'),
  getSpaces: provider('src/test/java/dev/espero/festival/web/CatalogControllerOpenApiTest.java', 'validatesEveryCatalogSuccessEnvelopeAndPayloadAgainstOpenApi'),
  getSpace: provider('src/test/java/dev/espero/festival/web/CatalogControllerOpenApiTest.java', 'validatesEveryCatalogSuccessEnvelopeAndPayloadAgainstOpenApi'),
  getMaps: provider('src/test/java/dev/espero/festival/web/CatalogControllerOpenApiTest.java', 'validatesEveryCatalogSuccessEnvelopeAndPayloadAgainstOpenApi'),
  getMap: provider('src/test/java/dev/espero/festival/web/CatalogControllerOpenApiTest.java', 'validatesEveryCatalogSuccessEnvelopeAndPayloadAgainstOpenApi'),
  getPins: provider('src/test/java/dev/espero/festival/web/CatalogControllerOpenApiTest.java', 'validatesEveryCatalogSuccessEnvelopeAndPayloadAgainstOpenApi'),
  getPlace: provider('src/test/java/dev/espero/festival/web/CatalogControllerOpenApiTest.java', 'validatesEveryCatalogSuccessEnvelopeAndPayloadAgainstOpenApi'),
  getTicketGuide: provider('src/test/java/dev/espero/festival/web/TicketGuideAccountFlowIntegrationTest.java', 'reflectsEachAccountChangeOnTheNextRequestWithANewEtag', 'hidesTheAccountAndChangesTheEtagAtTheDailyTransferClose'),
  getStampGuide: provider('src/test/java/dev/espero/festival/web/StampGuideControllerTest.java', 'returnsGuideMatchingApiV2Schema', 'throwsServiceUnavailableWhenGuideNotConfigured', 'rejectsUnreadyAndUnknownLocalesWithoutFallingBack'),
  verifyStampReceipt: provider('src/test/java/dev/espero/festival/web/CatalogControllerOpenApiTest.java', 'validatesStampReceiptSuccessAndRefusalAgainstOpenApi'),
  getAdminCrowding: provider('src/test/java/dev/espero/festival/web/CrowdingFlowIntegrationTest.java', 'savesWithTheCurrentEtagThenServesTheNewRepresentationAndRejectsStaleWrites'),
  putAdminCrowding: provider('src/test/java/dev/espero/festival/web/CrowdingControllerOpenApiTest.java', 'validatesMissingConcurrencyPreconditionAgainstThe428Contract'),
  getAdminNotices: provider('src/test/java/dev/espero/festival/web/NoticeFlowIntegrationTest.java', 'createsReadsUpdatesAndSoftDeletesANoticeThroughTheAdminApi'),
  postAdminNotice: provider('src/test/java/dev/espero/festival/web/NoticeFlowIntegrationTest.java', 'createsReadsUpdatesAndSoftDeletesANoticeThroughTheAdminApi', 'requiresIdempotencyKeyOnCreateAndIfMatchOnUpdate', 'rejectsInvalidNoticeInputShapes'),
  getAdminNotice: provider('src/test/java/dev/espero/festival/web/NoticeFlowIntegrationTest.java', 'createsReadsUpdatesAndSoftDeletesANoticeThroughTheAdminApi'),
  putAdminNotice: provider('src/test/java/dev/espero/festival/web/NoticeFlowIntegrationTest.java', 'createsReadsUpdatesAndSoftDeletesANoticeThroughTheAdminApi', 'requiresIdempotencyKeyOnCreateAndIfMatchOnUpdate', 'rejectsMismatchedIfMatchAsAnEditConflict', 'rejectsInvalidNoticeInputShapes'),
  deleteAdminNotice: provider('src/test/java/dev/espero/festival/web/NoticeFlowIntegrationTest.java', 'createsReadsUpdatesAndSoftDeletesANoticeThroughTheAdminApi'),
  getTemplates: provider('src/test/java/dev/espero/festival/web/NoticeFlowIntegrationTest.java', 'servesThePlaceholderTemplateInEveryLocaleToAdministratorsOnly'),
  getTemplate: provider('src/test/java/dev/espero/festival/web/NoticeFlowIntegrationTest.java', 'keepsTheTemplateANoticeStartedFromUntilThatTemplateIsRemoved'),
  getAdminGoods: provider('src/test/java/dev/espero/festival/web/AdminGoodsAvailabilityFlowIntegrationTest.java', 'listsMixedAndSoldOutGoodsForAnAuthenticatedAdminWithUnscopedKoreanMeta'),
  putAdminAvailability: provider('src/test/java/dev/espero/festival/web/AdminGoodsAvailabilityFlowIntegrationTest.java', 'updatesOnlyTheOwnedCombinationWithoutIfMatchAndReturnsTheCurrentAvailability', 'rejectsInvalidMissingLegacyAndExtraInput', 'hidesUnknownAndCrossOwnershipCombinationsBehindNotFound', 'requiresOneValidIdempotencyKey'),
  getAdminProducts: provider('src/test/java/dev/espero/festival/web/AdminGoodsAvailabilityFlowIntegrationTest.java', 'listsAdminProductsWithRawTranslationsOptionsAndKoreanMeta'),
  postAdminProduct: provider('src/test/java/dev/espero/festival/web/AdminGoodsProductCreationFlowIntegrationTest.java', 'createsOpenApiShapedOptionsProductAndExposesEveryReadModel'),
  getAdminProduct: provider('src/test/java/dev/espero/festival/web/AdminGoodsAvailabilityFlowIntegrationTest.java', 'getsAdminProductDetailWithStableStrongEtagAndConditionalRevalidation'),
  putAdminProduct: provider('src/test/java/dev/espero/festival/web/AdminGoodsProductCreationFlowIntegrationTest.java', 'updatesOptionsDifferentiallyAndReordersStableColorsSizesAndImages'),
  deleteAdminProduct: provider('src/test/java/dev/espero/festival/web/AdminGoodsProductCreationFlowIntegrationTest.java', 'hardDeletesProductDetachesMediaAndReplaysAfterTheRowIsGone'),
  postAdminGoodsImage: provider('src/test/java/dev/espero/festival/web/AdminGoodsImageUploadFlowIntegrationTest.java', 'uploadsAndReplaysTheSameImageWithOneMutationAndFreshMeta'),
  getGoodsImage: provider('src/test/java/dev/espero/festival/web/GoodsMediaControllerIntegrationTest.java', 'anonymouslyStreamsEveryAttachedVariantWithImmutableHeaders'),
  createAdminSession: provider('src/test/java/dev/espero/festival/web/AdminSessionControllerTest.java', 'loginReturnsAccessTokenAndSecureHttpOnlyRefreshCookie'),
  refreshAdminSession: provider('src/test/java/dev/espero/festival/web/AdminSessionControllerTest.java', 'refreshRotatesTheCookie'),
  deleteCurrentAdminSession: provider('src/test/java/dev/espero/festival/web/AdminSessionControllerTest.java', 'logoutRevokesSessionAndExpiresCookie'),
  getCurrentAdmin: provider('src/test/java/dev/espero/festival/web/AdminSessionControllerTest.java', 'meUsesAuthenticatedPrincipalWithoutParsingJwt'),
};

const operationScenarios = {
  getConfig: ['HTTP-01', 'HTTP-10', 'HTTP-19', 'HTTP-20'],
  getCrowding: ['HTTP-08', 'HTTP-10', 'HTTP-18', 'HTTP-21', 'HTTP-24'],
  getNotices: ['HTTP-10', 'HTTP-18'],
  getGoods: ['HTTP-18'],
  getGoodsAvailability: ['HTTP-18'],
  getGood: ['HTTP-18'],
  getGoodAvailability: ['HTTP-18'],
  getPaymentGuide: ['HTTP-18'],
  getLineup: ['HTTP-05', 'HTTP-14', 'HTTP-18', 'HTTP-19'],
  getArtist: ['HTTP-05', 'HTTP-14', 'HTTP-18'],
  getTimetable: ['HTTP-05', 'HTTP-14', 'HTTP-18', 'HTTP-19'],
  getPerformance: ['HTTP-05', 'HTTP-14', 'HTTP-18'],
  getProhibitedItems: ['HTTP-05', 'HTTP-18'],
  getSpaces: ['HTTP-02', 'HTTP-03', 'HTTP-04', 'HTTP-14', 'HTTP-18'],
  getSpace: ['HTTP-02', 'HTTP-03', 'HTTP-18'],
  getMaps: ['HTTP-03', 'HTTP-04', 'HTTP-14', 'HTTP-18'],
  getMap: ['HTTP-04', 'HTTP-18'],
  getPins: ['HTTP-03', 'HTTP-04', 'HTTP-18'],
  getPlace: ['HTTP-03', 'HTTP-04', 'HTTP-18'],
  getTicketGuide: ['HTTP-06', 'HTTP-11', 'HTTP-18', 'HTTP-23'],
  getStampGuide: ['HTTP-06'],
  verifyStampReceipt: ['HTTP-06'],
  getAdminCrowding: ['HTTP-08', 'HTTP-09', 'HTTP-12', 'HTTP-16', 'HTTP-24'],
  putAdminCrowding: ['HTTP-08', 'HTTP-09', 'HTTP-12', 'HTTP-16', 'HTTP-24'],
  getAdminNotices: [],
  postAdminNotice: [],
  getAdminNotice: [],
  putAdminNotice: [],
  deleteAdminNotice: [],
  getTemplates: [],
  getTemplate: [],
  getAdminGoods: [],
  putAdminAvailability: [],
  getAdminProducts: [],
  postAdminProduct: [],
  getAdminProduct: [],
  putAdminProduct: [],
  deleteAdminProduct: [],
  postAdminGoodsImage: [],
  getGoodsImage: [],
  createAdminSession: ['HTTP-07', 'HTTP-12', 'HTTP-22'],
  refreshAdminSession: ['HTTP-07', 'HTTP-12'],
  deleteCurrentAdminSession: ['HTTP-07', 'HTTP-12'],
  getCurrentAdmin: ['HTTP-07', 'HTTP-12'],
};

const operationUnresolvedReasons = {
  getNotices: 'No release scenario currently traverses the notice list; the provider integration test remains the source of live coverage.',
  getGoods: 'No release scenario currently traverses the goods catalog; the provider integration test remains the source of live coverage.',
  getGoodsAvailability: 'No release scenario currently traverses goods availability; the provider integration test remains the source of live coverage.',
  getGood: 'No release scenario currently traverses a goods detail; the provider integration test remains the source of live coverage.',
  getGoodAvailability: 'No release scenario currently traverses a goods availability detail; the provider integration test remains the source of live coverage.',
  getPaymentGuide: 'No release scenario currently traverses the goods payment guide; the provider integration test remains the source of live coverage.',
  getAdminNotices: 'No HTTP/OPS release scenario currently covers admin notice reads.',
  postAdminNotice: 'No HTTP/OPS release scenario currently covers admin notice creation; provider tests cover success, authorization boundaries, idempotency, and invalid input.',
  getAdminNotice: 'No HTTP/OPS release scenario currently covers an admin notice detail.',
  putAdminNotice: 'No HTTP/OPS release scenario currently covers admin notice updates; provider tests cover success, If-Match conflicts, authorization boundaries, and invalid input.',
  deleteAdminNotice: 'No HTTP/OPS release scenario currently covers admin notice deletion.',
  getTemplates: 'No HTTP/OPS release scenario currently covers admin notice templates.',
  getTemplate: 'No HTTP/OPS release scenario currently covers an admin notice template detail.',
  getAdminGoods: 'No HTTP/OPS release scenario currently covers admin goods reads.',
  putAdminAvailability: 'No HTTP/OPS release scenario currently covers admin availability writes; provider tests cover success, idempotency, validation, authorization, and unknown combinations.',
  getAdminProducts: 'No HTTP/OPS release scenario currently covers admin product reads.',
  postAdminProduct: 'No HTTP/OPS release scenario currently covers admin product creation.',
  getAdminProduct: 'No HTTP/OPS release scenario currently covers an admin product detail.',
  putAdminProduct: 'No HTTP/OPS release scenario currently covers admin product updates.',
  deleteAdminProduct: 'No HTTP/OPS release scenario currently covers admin product deletion.',
  postAdminGoodsImage: 'No HTTP/OPS release scenario currently covers admin goods media upload.',
  getGoodsImage: 'No HTTP/OPS release scenario currently covers goods media retrieval.',
};

const expectedScenarioIds = [
  ...Array.from({ length: 24 }, (_, index) => `HTTP-${String(index + 1).padStart(2, '0')}`),
  ...Array.from({ length: 20 }, (_, index) => `OPS-${String(index + 1).padStart(2, '0')}`),
];

export const releaseScenarioIds = Object.freeze(expectedScenarioIds);
export const releaseTestAnchor = 'Postgresql17MigrationReleaseTest';
export const releaseTestAnchorFile = 'src/test/java/dev/espero/festival/persistence/Postgresql17MigrationReleaseTest.java';

const testClassName = file => file.split(/[\\/]/).pop().replace(/\.java$/, '');

export const buildReleaseTestSelection = metadata => {
  const classes = new Set([releaseTestAnchor]);
  for (const operation of metadata?.operations ?? []) {
    if (operation.status !== 'live') continue;
    for (const test of operation.testMapping?.tests ?? []) classes.add(testClassName(test.file));
  }
  for (const scenario of metadata?.scenarios ?? []) {
    for (const test of scenario.testMapping?.tests ?? []) classes.add(testClassName(test.file));
  }
  return [...classes].sort((left, right) => left.localeCompare(right));
};

export const buildReleaseOperationCoverage = spec => {
  const operations = [];
  for (const [path, pathItem] of Object.entries(spec.paths ?? {})) {
    for (const [method, operation] of Object.entries(pathItem)) {
      if (!operation || typeof operation !== 'object' || !operation.operationId) continue;
      const operationId = operation.operationId;
      const mapping = providerTests[operationId];
      if (!mapping) throw new Error(`Missing release coverage mapping for ${operationId}`);
      const scenarioIds = operationScenarios[operationId] ?? [];
      const status = mapping.kind === 'scope-block' ? 'non-exposure' : 'live';
      const unresolvedReason = status === 'non-exposure'
        ? mapping.reason
        : (scenarioIds.length ? null : operationUnresolvedReasons[operationId] ?? 'No HTTP/OPS scenario mapping is recorded yet.');
      operations.push({
        operationId,
        path,
        method: method.toUpperCase(),
        status,
        testMapping: mapping,
        scenarioIds,
        unresolvedReason,
      });
    }
  }
  const scenarios = expectedScenarioIds.map(id => ({
    id,
    testMapping: { kind: 'release-scenario', tests: scenarioTests[id] },
    operationIds: operations.filter(operation => operation.scenarioIds.includes(id)).map(operation => operation.operationId),
  }));
  return {
    schemaVersion: 1,
    source: 'api-v2/openapi.json',
    operationCount: operations.length,
    operations,
    scenarioCount: scenarios.length,
    scenarios,
  };
};

const issue = (issues, message) => issues.push(message);

const expectedOperationKey = ({ operationId, path, method }) => `${method.toUpperCase()} ${path}#${operationId}`;

export const validateReleaseOperationCoverage = (spec, metadata, { repositoryRoot } = {}) => {
  const issues = [];
  const checkTests = (label, tests) => {
    for (const test of tests ?? []) {
      if (!test.file || !test.test) issue(issues, `${label} has an incomplete test mapping`);
      if (repositoryRoot && test.file && test.test) {
        const file = nodePath.join(repositoryRoot, test.file);
        if (!existsSync(file)) issue(issues, `${label} test file does not exist: ${test.file}`);
        else if (!readFileSync(file, 'utf8').includes(test.test)) issue(issues, `${label} test method is not present: ${test.test}`);
      }
    }
  };
  if (!metadata || metadata.schemaVersion !== 1) issue(issues, 'metadata.schemaVersion must be 1');
  if (metadata?.source !== 'api-v2/openapi.json') issue(issues, 'metadata.source must be api-v2/openapi.json');
  const specOperations = [];
  const specOperationIds = new Set();
  for (const [path, pathItem] of Object.entries(spec?.paths ?? {})) {
    for (const [method, operation] of Object.entries(pathItem)) {
      if (!operation || typeof operation !== 'object' || !operation.operationId) continue;
      if (specOperationIds.has(operation.operationId)) issue(issues, `OpenAPI operationId is duplicated: ${operation.operationId}`);
      specOperationIds.add(operation.operationId);
      specOperations.push({ operationId: operation.operationId, path, method: method.toUpperCase() });
    }
  }
  const rows = Array.isArray(metadata?.operations) ? metadata.operations : [];
  if (metadata?.operationCount !== specOperations.length) issue(issues, `operationCount ${metadata?.operationCount} does not match OpenAPI ${specOperations.length}`);
  const rowKeys = new Set();
  for (const row of rows) {
    const key = expectedOperationKey(row);
    if (rowKeys.has(key)) issue(issues, `duplicate operation row ${key}`);
    rowKeys.add(key);
    if (!['live', 'non-exposure'].includes(row.status)) issue(issues, `${key} has invalid status ${row.status}`);
    if (!Array.isArray(row.scenarioIds) || row.scenarioIds.some(id => !expectedScenarioIds.includes(id))) issue(issues, `${key} has invalid scenarioIds`);
    if (row.status === 'live') {
      if (row.testMapping?.kind !== 'provider' || !Array.isArray(row.testMapping.tests) || row.testMapping.tests.length === 0) issue(issues, `${key} must have a provider test mapping`);
      if (row.unresolvedReason !== null && typeof row.unresolvedReason !== 'string') issue(issues, `${key} unresolvedReason must be null or a string`);
    } else {
      if (row.testMapping?.kind !== 'scope-block') issue(issues, `${key} must have a scope-block mapping`);
      if (typeof row.unresolvedReason !== 'string' || !row.unresolvedReason.trim()) issue(issues, `${key} must explain its non-exposure reason`);
    }
    checkTests(key, row.testMapping?.tests);
  }
  const specKeys = new Set(specOperations.map(expectedOperationKey));
  for (const operation of specOperations) if (!rowKeys.has(expectedOperationKey(operation))) issue(issues, `OpenAPI operation is not classified: ${expectedOperationKey(operation)}`);
  for (const row of rows) if (!specKeys.has(expectedOperationKey(row))) issue(issues, `coverage row is not in OpenAPI: ${expectedOperationKey(row)}`);
  if (rows.length !== specOperations.length) issue(issues, `coverage has ${rows.length} rows for ${specOperations.length} OpenAPI operations`);
  const scenarios = Array.isArray(metadata?.scenarios) ? metadata.scenarios : [];
  if (metadata?.scenarioCount !== expectedScenarioIds.length || scenarios.length !== expectedScenarioIds.length) issue(issues, 'scenario inventory must contain HTTP-01..24 and OPS-01..20 exactly once');
  const scenarioIds = new Set();
  for (const scenario of scenarios) {
    if (scenarioIds.has(scenario.id)) issue(issues, `duplicate scenario ${scenario.id}`);
    scenarioIds.add(scenario.id);
    if (!expectedScenarioIds.includes(scenario.id)) issue(issues, `unknown scenario ${scenario.id}`);
    if (scenario.testMapping?.kind !== 'release-scenario' || !Array.isArray(scenario.testMapping.tests) || scenario.testMapping.tests.length === 0) issue(issues, `${scenario.id} must have a release test mapping`);
    checkTests(scenario.id, scenario.testMapping?.tests);
    if (!Array.isArray(scenario.operationIds)) issue(issues, `${scenario.id} operationIds must be an array`);
    for (const operationId of scenario.operationIds ?? []) if (!specOperations.some(operation => operation.operationId === operationId)) issue(issues, `${scenario.id} references unknown operation ${operationId}`);
  }
  for (const id of expectedScenarioIds) if (!scenarioIds.has(id)) issue(issues, `missing scenario ${id}`);
  return issues;
};

export const releaseCoverageSource = {
  providerTests,
  operationScenarios,
  scenarioTests,
  operationUnresolvedReasons,
  releaseScenarioIds,
};
