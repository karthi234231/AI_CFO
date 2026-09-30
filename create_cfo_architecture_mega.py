from pathlib import Path

"""
CFO MEGA ARCHITECTURE FILE SCAFFOLDER
====================================

Run from:
    C:/cfo

Command:
    python create_cfo_architecture_mega.py

Purpose:
    Create the complete Phase-0 architecture FILES with safe placeholders.

IMPORTANT:
    - Existing folders are NOT deleted.
    - Existing files are NOT overwritten.
    - Missing folders are created automatically.
    - Missing files are created automatically.
    - Safe to run repeatedly.
    - Package root is YOUR actual package:
          com.fintech.cfo

The supplied architecture document uses "com.company.cfo" as a conceptual
placeholder. This script uses the package already present in your project:
    src/main/java/com/fintech/cfo

The script also preserves your current top-level modules:
    ai, contract, evidence, financial, financialtruth, identity,
    ingestion, investigation, opportunity, outcome, platform, reporting,
    shared

It adds the missing target-architecture packages/files where necessary.
"""

from pathlib import Path
import sys

# ============================================================
# PROJECT ROOT
# ============================================================

PROJECT_ROOT = Path(__file__).resolve().parent

JAVA_ROOT = PROJECT_ROOT / "src" / "main" / "java" / "com" / "fintech" / "cfo"
TEST_ROOT = PROJECT_ROOT / "src" / "test" / "java" / "com" / "fintech" / "cfo"

# ============================================================
# EXISTING + TARGET ARCHITECTURE DIRECTORIES
# ============================================================
#
# Your existing tree is intentionally included.
# Target architecture directories missing from it are included too.
#
# No existing directory will be harmed.
# ============================================================

DIRECTORIES = [
    # --------------------------------------------------------
    # Documentation
    # --------------------------------------------------------
    "docs",
    "docs/architecture",
    "docs/decisions",

    # --------------------------------------------------------
    # ROOT Java package
    # --------------------------------------------------------
    "src/main/java/com/fintech/cfo",

    # --------------------------------------------------------
    # SHARED
    # Existing: domain, exception, security, util
    # Added: enums
    # --------------------------------------------------------
    "src/main/java/com/fintech/cfo/shared",
    "src/main/java/com/fintech/cfo/shared/domain",
    "src/main/java/com/fintech/cfo/shared/enums",
    "src/main/java/com/fintech/cfo/shared/exception",
    "src/main/java/com/fintech/cfo/shared/security",
    "src/main/java/com/fintech/cfo/shared/util",

    # --------------------------------------------------------
    # PLATFORM
    # Existing: audit, config, observability, storage, web
    # Added: persistence, idempotency
    # --------------------------------------------------------
    "src/main/java/com/fintech/cfo/platform",
    "src/main/java/com/fintech/cfo/platform/audit",
    "src/main/java/com/fintech/cfo/platform/config",
    "src/main/java/com/fintech/cfo/platform/observability",
    "src/main/java/com/fintech/cfo/platform/storage",
    "src/main/java/com/fintech/cfo/platform/web",
    "src/main/java/com/fintech/cfo/platform/persistence",
    "src/main/java/com/fintech/cfo/platform/idempotency",

    # --------------------------------------------------------
    # ACCESS - exact target architecture module
    # Kept in addition to your existing identity module so this
    # scaffolder covers the architecture document literally.
    # --------------------------------------------------------
    "src/main/java/com/fintech/cfo/access",
    "src/main/java/com/fintech/cfo/access/controller",
    "src/main/java/com/fintech/cfo/access/dto",
    "src/main/java/com/fintech/cfo/access/model",
    "src/main/java/com/fintech/cfo/access/enums",
    "src/main/java/com/fintech/cfo/access/repository",
    "src/main/java/com/fintech/cfo/access/service",
    "src/main/java/com/fintech/cfo/access/security",

    # --------------------------------------------------------
    # IDENTITY
    #
    # Current project calls this "identity".
    # Conceptual target architecture calls this "access".
    # We preserve identity and place access responsibilities there.
    # --------------------------------------------------------
    "src/main/java/com/fintech/cfo/identity",
    "src/main/java/com/fintech/cfo/identity/controller",
    "src/main/java/com/fintech/cfo/identity/dto",
    "src/main/java/com/fintech/cfo/identity/model",
    "src/main/java/com/fintech/cfo/identity/repository",
    "src/main/java/com/fintech/cfo/identity/security",
    "src/main/java/com/fintech/cfo/identity/service",
    "src/main/java/com/fintech/cfo/identity/enums",

    # --------------------------------------------------------
    # INGESTION
    # Existing also has security, preserved.
    # --------------------------------------------------------
    "src/main/java/com/fintech/cfo/ingestion",
    "src/main/java/com/fintech/cfo/ingestion/controller",
    "src/main/java/com/fintech/cfo/ingestion/dto",
    "src/main/java/com/fintech/cfo/ingestion/enums",
    "src/main/java/com/fintech/cfo/ingestion/model",
    "src/main/java/com/fintech/cfo/ingestion/parser",
    "src/main/java/com/fintech/cfo/ingestion/repository",
    "src/main/java/com/fintech/cfo/ingestion/security",
    "src/main/java/com/fintech/cfo/ingestion/service",
    "src/main/java/com/fintech/cfo/ingestion/validator",

    # --------------------------------------------------------
    # FINANCIAL
    # --------------------------------------------------------
    "src/main/java/com/fintech/cfo/financial",
    "src/main/java/com/fintech/cfo/financial/controller",
    "src/main/java/com/fintech/cfo/financial/dto",
    "src/main/java/com/fintech/cfo/financial/enums",
    "src/main/java/com/fintech/cfo/financial/mapper",
    "src/main/java/com/fintech/cfo/financial/model",
    "src/main/java/com/fintech/cfo/financial/normalization",
    "src/main/java/com/fintech/cfo/financial/repository",
    "src/main/java/com/fintech/cfo/financial/service",

    # --------------------------------------------------------
    # CONTRACT
    # --------------------------------------------------------
    "src/main/java/com/fintech/cfo/contract",
    "src/main/java/com/fintech/cfo/contract/controller",
    "src/main/java/com/fintech/cfo/contract/dto",
    "src/main/java/com/fintech/cfo/contract/extraction",
    "src/main/java/com/fintech/cfo/contract/model",
    "src/main/java/com/fintech/cfo/contract/repository",
    "src/main/java/com/fintech/cfo/contract/service",
    "src/main/java/com/fintech/cfo/contract/enums",

    # --------------------------------------------------------
    # FINANCIAL TRUTH
    # Existing: controller, dto, enums, model, repository, rules, service
    # Added: calculator
    # --------------------------------------------------------
    "src/main/java/com/fintech/cfo/financialtruth",
    "src/main/java/com/fintech/cfo/financialtruth/controller",
    "src/main/java/com/fintech/cfo/financialtruth/dto",
    "src/main/java/com/fintech/cfo/financialtruth/enums",
    "src/main/java/com/fintech/cfo/financialtruth/model",
    "src/main/java/com/fintech/cfo/financialtruth/repository",
    "src/main/java/com/fintech/cfo/financialtruth/rules",
    "src/main/java/com/fintech/cfo/financialtruth/service",
    "src/main/java/com/fintech/cfo/financialtruth/calculator",

    # --------------------------------------------------------
    # EVIDENCE
    # --------------------------------------------------------
    "src/main/java/com/fintech/cfo/evidence",
    "src/main/java/com/fintech/cfo/evidence/controller",
    "src/main/java/com/fintech/cfo/evidence/dto",
    "src/main/java/com/fintech/cfo/evidence/model",
    "src/main/java/com/fintech/cfo/evidence/repository",
    "src/main/java/com/fintech/cfo/evidence/service",
    "src/main/java/com/fintech/cfo/evidence/enums",

    # --------------------------------------------------------
    # OPPORTUNITY
    # Existing top-level folder is empty.
    # --------------------------------------------------------
    "src/main/java/com/fintech/cfo/opportunity",
    "src/main/java/com/fintech/cfo/opportunity/controller",
    "src/main/java/com/fintech/cfo/opportunity/dto",
    "src/main/java/com/fintech/cfo/opportunity/model",
    "src/main/java/com/fintech/cfo/opportunity/enums",
    "src/main/java/com/fintech/cfo/opportunity/repository",
    "src/main/java/com/fintech/cfo/opportunity/service",

    # --------------------------------------------------------
    # OUTCOME
    #
    # Current project calls this "outcome".
    # Target architecture calls the Phase-0 action/value area "value".
    # Preserve outcome and create value as an additional module.
    # --------------------------------------------------------
    "src/main/java/com/fintech/cfo/outcome",
    "src/main/java/com/fintech/cfo/outcome/controller",
    "src/main/java/com/fintech/cfo/outcome/dto",
    "src/main/java/com/fintech/cfo/outcome/enums",
    "src/main/java/com/fintech/cfo/outcome/model",
    "src/main/java/com/fintech/cfo/outcome/repository",
    "src/main/java/com/fintech/cfo/outcome/service",

    "src/main/java/com/fintech/cfo/value",
    "src/main/java/com/fintech/cfo/value/controller",
    "src/main/java/com/fintech/cfo/value/dto",
    "src/main/java/com/fintech/cfo/value/enums",
    "src/main/java/com/fintech/cfo/value/model",
    "src/main/java/com/fintech/cfo/value/repository",
    "src/main/java/com/fintech/cfo/value/service",

    # --------------------------------------------------------
    # INVESTIGATION
    #
    # Current module retained. Architecture source does not
    # define this module, so placeholders are based only on
    # the directory structure the user already created.
    # --------------------------------------------------------
    "src/main/java/com/fintech/cfo/investigation",
    "src/main/java/com/fintech/cfo/investigation/controller",
    "src/main/java/com/fintech/cfo/investigation/dto",
    "src/main/java/com/fintech/cfo/investigation/enums",
    "src/main/java/com/fintech/cfo/investigation/model",
    "src/main/java/com/fintech/cfo/investigation/repository",
    "src/main/java/com/fintech/cfo/investigation/service",

    # --------------------------------------------------------
    # AI
    # Existing: controller, dto, pdf, service
    # Target adds: model, enums, client, extraction
    # --------------------------------------------------------
    "src/main/java/com/fintech/cfo/ai",
    "src/main/java/com/fintech/cfo/ai/controller",
    "src/main/java/com/fintech/cfo/ai/dto",
    "src/main/java/com/fintech/cfo/ai/pdf",
    "src/main/java/com/fintech/cfo/ai/service",
    "src/main/java/com/fintech/cfo/ai/model",
    "src/main/java/com/fintech/cfo/ai/enums",
    "src/main/java/com/fintech/cfo/ai/client",
    "src/main/java/com/fintech/cfo/ai/extraction",

    # --------------------------------------------------------
    # REPORTING
    # --------------------------------------------------------
    "src/main/java/com/fintech/cfo/reporting",
    "src/main/java/com/fintech/cfo/reporting/controller",
    "src/main/java/com/fintech/cfo/reporting/dto",
    "src/main/java/com/fintech/cfo/reporting/model",
    "src/main/java/com/fintech/cfo/reporting/enums",
    "src/main/java/com/fintech/cfo/reporting/service",
    "src/main/java/com/fintech/cfo/reporting/pdf",

    # --------------------------------------------------------
    # PROCESSING
    # New target module for heavy/background work.
    # --------------------------------------------------------
    "src/main/java/com/fintech/cfo/processing",
    "src/main/java/com/fintech/cfo/processing/ingestion",
    "src/main/java/com/fintech/cfo/processing/financialtruth",
    "src/main/java/com/fintech/cfo/processing/reporting",
    "src/main/java/com/fintech/cfo/processing/common",

    # --------------------------------------------------------
    # RESOURCES
    # --------------------------------------------------------
    "src/main/resources",
    "src/main/resources/db",
    "src/main/resources/db/migration",
    "src/main/resources/prompts",

    # --------------------------------------------------------
    # TESTS
    # --------------------------------------------------------
    "src/test/java/com/fintech/cfo/architecture",
    "src/test/java/com/fintech/cfo/security",
    "src/test/java/com/fintech/cfo/ingestion",
    "src/test/java/com/fintech/cfo/identity",
    "src/test/java/com/fintech/cfo/financial",
    "src/test/java/com/fintech/cfo/contract",
    "src/test/java/com/fintech/cfo/financialtruth",
    "src/test/java/com/fintech/cfo/evidence",
    "src/test/java/com/fintech/cfo/opportunity",
    "src/test/java/com/fintech/cfo/value",
    "src/test/java/com/fintech/cfo/outcome",
    "src/test/java/com/fintech/cfo/api",
    "src/test/resources",
]


# ============================================================
# FILE MAP
# ============================================================
#
# Every file below gets:
#
# package ...
#
# /**
#  * TODO...
#  */
# public class ...
#
# Existing files are NOT overwritten.
# ============================================================

JAVA_FILES = {

    # ========================================================
    # SHARED
    # ========================================================

    "shared/domain": [
        "Money.java",
        "CurrencyCode.java",
        "DateRange.java",
        "TenantId.java",
        "UserId.java",
        "OrganizationId.java",
        "SourceReference.java",
        "VersionedValue.java",
    ],

    "shared/enums": [
        "Currency.java",
        "Status.java",
        "ProcessingStatus.java",
    ],

    "shared/exception": [
        "DomainException.java",
        "ValidationException.java",
        "NotFoundException.java",
        "ConflictException.java",
        "AccessDeniedException.java",
        "BusinessRuleException.java",
    ],

    # Existing security directory is retained.
    "shared/security": [
        "SecurityPrincipal.java",
        "SecurityContext.java",
    ],

    "shared/util": [
        "IdGenerator.java",
        "HashUtils.java",
        "DateTimeUtils.java",
    ],


    # ========================================================
    # PLATFORM
    # ========================================================

    "platform/config": [
        "JacksonConfig.java",
        "OpenApiConfig.java",
        "TransactionConfig.java",
        "AsyncConfig.java",
        "ApplicationProperties.java",
    ],

    "platform/web": [
        "GlobalExceptionHandler.java",
        "ApiErrorResponse.java",
        "ApiResponse.java",
        "CorrelationIdFilter.java",
        "RequestLoggingFilter.java",
    ],

    "platform/audit": [
        "AuditService.java",
        "AuditEvent.java",
        "AuditEventType.java",
        "AuditRepository.java",
        "AuditEventEntity.java",
        "AuditEventJpaRepository.java",
    ],

    "platform/observability": [
        "MetricsConfiguration.java",
        "TracingConfiguration.java",
        "BusinessMetrics.java",
    ],

    "platform/storage": [
        "ObjectStoragePort.java",
        "ObjectStorageService.java",
        "StorageObject.java",
    ],

    "platform/persistence": [
        "JpaConfiguration.java",
        "PersistenceAuditListener.java",
    ],

    "platform/idempotency": [
        "IdempotencyService.java",
        "IdempotencyRepository.java",
        "IdempotencyFilter.java",
    ],


    # ========================================================
    # IDENTITY / ACCESS
    # ========================================================

    # ========================================================
    # ACCESS - exact target architecture
    # ========================================================

    "access/controller": [
        "UserController.java",
        "OrganizationController.java",
        "RoleController.java",
    ],

    "access/dto": [
        "UserResponse.java",
        "OrganizationResponse.java",
        "RoleResponse.java",
        "PermissionResponse.java",
    ],

    "access/model": [
        "User.java",
        "Organization.java",
        "Membership.java",
        "Role.java",
        "Permission.java",
    ],

    "access/enums": [
        "UserStatus.java",
        "RoleType.java",
        "PermissionType.java",
    ],

    "access/repository": [
        "UserRepository.java",
        "OrganizationRepository.java",
        "MembershipRepository.java",
        "RoleRepository.java",
    ],

    "access/service": [
        "UserService.java",
        "OrganizationService.java",
        "AuthorizationService.java",
        "TenantAccessService.java",
    ],

    "access/security": [
        "SecurityConfig.java",
        "JwtAuthenticationConverter.java",
        "CurrentUserProvider.java",
        "CurrentUser.java",
        "TenantContext.java",
        "TenantContextFilter.java",
        "SecurityHeadersConfig.java",
    ],



    "identity/controller": [
        "UserController.java",
        "OrganizationController.java",
        "RoleController.java",
    ],

    "identity/dto": [
        "UserResponse.java",
        "OrganizationResponse.java",
        "RoleResponse.java",
        "PermissionResponse.java",
    ],

    "identity/model": [
        "User.java",
        "Organization.java",
        "Membership.java",
        "Role.java",
        "Permission.java",
    ],

    "identity/enums": [
        "UserStatus.java",
        "RoleType.java",
        "PermissionType.java",
    ],

    "identity/repository": [
        "UserRepository.java",
        "OrganizationRepository.java",
        "MembershipRepository.java",
        "RoleRepository.java",
    ],

    "identity/service": [
        "UserService.java",
        "OrganizationService.java",
        "AuthorizationService.java",
        "TenantAccessService.java",
    ],

    "identity/security": [
        "SecurityConfig.java",
        "JwtAuthenticationConverter.java",
        "CurrentUserProvider.java",
        "CurrentUser.java",
        "TenantContext.java",
        "TenantContextFilter.java",
        "SecurityHeadersConfig.java",
    ],


    # ========================================================
    # INGESTION
    # ========================================================

    "ingestion/controller": [
        "IngestionController.java",
    ],

    "ingestion/dto": [
        "StartIngestionRequest.java",
        "IngestionResponse.java",
        "IngestionStatusResponse.java",
        "IngestionErrorResponse.java",
        "UploadedFileResponse.java",
    ],

    "ingestion/model": [
        "IngestionRun.java",
        "SourceFile.java",
        "SourceRecord.java",
        "IngestionError.java",
    ],

    "ingestion/enums": [
        "IngestionStatus.java",
        "IngestionStage.java",
        "FileType.java",
        "IngestionErrorType.java",
    ],

    "ingestion/repository": [
        "IngestionRepository.java",
        "SourceFileRepository.java",
        "IngestionErrorRepository.java",
    ],

    "ingestion/service": [
        "IngestionService.java",
        "IngestionOrchestrator.java",
        "FileSecurityService.java",
        "FileValidationService.java",
        "IngestionStatusService.java",
    ],

    "ingestion/parser": [
        "FileParser.java",
        "CsvFileParser.java",
        "ExcelFileParser.java",
        "ParsedRecord.java",
    ],

    "ingestion/security": [
        "FileUploadSecurityService.java",
        "MalwareScanService.java",
        "UploadAuthorizationService.java",
    ],

    "ingestion/validator": [
        "SchemaValidator.java",
        "DataQualityValidator.java",
        "DuplicateValidator.java",
        "RequiredFieldValidator.java",
        "DataTypeValidator.java",
    ],


    # ========================================================
    # FINANCIAL
    # ========================================================

    "financial/controller": [
        "CustomerController.java",
        "ProductController.java",
        "InvoiceController.java",
    ],

    "financial/dto": [
        "CustomerResponse.java",
        "ProductResponse.java",
        "InvoiceResponse.java",
        "InvoiceLineResponse.java",
        "TransactionResponse.java",
    ],

    "financial/model": [
        "Customer.java",
        "Product.java",
        "Invoice.java",
        "InvoiceLine.java",
        "FinancialTransaction.java",
        "AccountingPeriod.java",
    ],

    "financial/enums": [
        "InvoiceStatus.java",
        "TransactionType.java",
        "DocumentType.java",
    ],

    "financial/repository": [
        "CustomerRepository.java",
        "ProductRepository.java",
        "InvoiceRepository.java",
        "InvoiceLineRepository.java",
        "FinancialTransactionRepository.java",
    ],

    "financial/service": [
        "CustomerService.java",
        "ProductService.java",
        "InvoiceService.java",
        "FinancialDataService.java",
    ],

    "financial/normalization": [
        "FinancialDataNormalizer.java",
        "CustomerNormalizer.java",
        "ProductNormalizer.java",
        "InvoiceNormalizer.java",
    ],

    "financial/mapper": [
        "CustomerMapper.java",
        "ProductMapper.java",
        "InvoiceMapper.java",
    ],


    # ========================================================
    # CONTRACT
    # ========================================================

    "contract/controller": [
        "ContractController.java",
        "ContractTermController.java",
    ],

    "contract/dto": [
        "ContractResponse.java",
        "ContractTermResponse.java",
        "PricingTermResponse.java",
        "DiscountTermResponse.java",
    ],

    "contract/model": [
        "Contract.java",
        "ContractTerm.java",
        "PricingTerm.java",
        "DiscountTerm.java",
        "CommercialRule.java",
    ],

    "contract/enums": [
        "ContractStatus.java",
        "ContractTermType.java",
        "PricingType.java",
        "DiscountType.java",
    ],

    "contract/repository": [
        "ContractRepository.java",
        "ContractTermRepository.java",
        "CommercialRuleRepository.java",
    ],

    "contract/service": [
        "ContractService.java",
        "ContractTermService.java",
        "CommercialRuleService.java",
    ],

    "contract/extraction": [
        "ContractTermExtractionService.java",
        "ExtractedContractTerm.java",
    ],


    # ========================================================
    # FINANCIAL TRUTH
    # ========================================================

    "financialtruth/controller": [
        "CalculationController.java",
        "CalculationRunController.java",
    ],

    "financialtruth/dto": [
        "RunCalculationRequest.java",
        "CalculationResponse.java",
        "CalculationRunResponse.java",
        "VarianceResponse.java",
        "FinancialImpactResponse.java",
    ],

    "financialtruth/model": [
        "CalculationRun.java",
        "CalculationResult.java",
        "CalculationInput.java",
        "ExpectedValue.java",
        "ActualValue.java",
        "Variance.java",
        "FinancialImpact.java",
    ],

    "financialtruth/enums": [
        "CalculationStatus.java",
        "CalculationType.java",
        "VarianceType.java",
        "CalculationConfidence.java",
    ],

    "financialtruth/rules": [
        "FinancialRule.java",
        "PricingVarianceRule.java",
        "DiscountVarianceRule.java",
        "RuleContext.java",
        "RuleEvaluationResult.java",
    ],

    "financialtruth/calculator": [
        "FinancialTruthEngine.java",
        "ExpectedAmountCalculator.java",
        "ActualAmountCalculator.java",
        "VarianceCalculator.java",
        "ImpactAggregator.java",
    ],

    "financialtruth/service": [
        "CalculationService.java",
        "CalculationRunService.java",
        "ReproducibilityService.java",
    ],

    "financialtruth/repository": [
        "CalculationRunRepository.java",
        "CalculationResultRepository.java",
    ],


    # ========================================================
    # EVIDENCE
    # ========================================================

    "evidence/controller": [
        "EvidenceController.java",
        "LineageController.java",
    ],

    "evidence/dto": [
        "EvidenceResponse.java",
        "EvidenceDetailResponse.java",
        "LineageResponse.java",
    ],

    "evidence/model": [
        "Evidence.java",
        "EvidenceReference.java",
        "EvidenceArtifact.java",
        "LineageNode.java",
        "LineageEdge.java",
        "EvidenceSnapshot.java",
    ],

    "evidence/enums": [
        "EvidenceType.java",
        "SourceType.java",
        "LineageRelationType.java",
    ],

    "evidence/repository": [
        "EvidenceRepository.java",
        "EvidenceReferenceRepository.java",
        "LineageRepository.java",
    ],

    "evidence/service": [
        "EvidenceService.java",
        "LineageService.java",
        "EvidenceSnapshotService.java",
    ],


    # ========================================================
    # OPPORTUNITY
    # ========================================================

    "opportunity/controller": [
        "OpportunityController.java",
        "OpportunityReviewController.java",
        "OpportunityAssignmentController.java",
    ],

    "opportunity/dto": [
        "OpportunityResponse.java",
        "OpportunityDetailResponse.java",
        "OpportunitySummaryResponse.java",
        "ValidateOpportunityRequest.java",
        "RejectOpportunityRequest.java",
        "ChallengeOpportunityRequest.java",
        "AssignOpportunityRequest.java",
    ],

    "opportunity/model": [
        "EconomicOpportunity.java",
        "OpportunityFinding.java",
        "OpportunityImpact.java",
        "OpportunityReview.java",
        "OpportunityAssignment.java",
        "OpportunityLifecycleEvent.java",
    ],

    "opportunity/enums": [
        "OpportunityType.java",
        "OpportunityStatus.java",
        "ValidationStatus.java",
        "OpportunityPriority.java",
        "OpportunityConfidence.java",
        "ReviewDecision.java",
    ],

    "opportunity/repository": [
        "OpportunityRepository.java",
        "OpportunityReviewRepository.java",
        "OpportunityLifecycleRepository.java",
    ],

    "opportunity/service": [
        "OpportunityDetectionService.java",
        "OpportunityService.java",
        "OpportunityReviewService.java",
        "OpportunityValidationService.java",
        "OpportunityLifecycleService.java",
    ],


    # ========================================================
    # VALUE
    # ========================================================

    "value/controller": [
        "ActionController.java",
        "OutcomeController.java",
    ],

    "value/dto": [
        "CreateActionRequest.java",
        "ActionResponse.java",
        "RecordOutcomeRequest.java",
        "OutcomeResponse.java",
        "RealizedValueResponse.java",
    ],

    "value/model": [
        "ActionPlan.java",
        "ActionExecution.java",
        "Outcome.java",
        "RealizedValue.java",
        "ValueAttribution.java",
    ],

    "value/enums": [
        "ActionStatus.java",
        "OutcomeStatus.java",
        "RealizationStatus.java",
        "AttributionMethod.java",
    ],

    "value/repository": [
        "ActionRepository.java",
        "OutcomeRepository.java",
        "RealizedValueRepository.java",
    ],

    "value/service": [
        "ActionService.java",
        "OutcomeService.java",
        "RealizedValueService.java",
        "ValueAttributionService.java",
    ],


    # ========================================================
    # OUTCOME - CURRENT MODULE
    # ========================================================
    #
    # The source architecture does not define file names for this
    # existing current module. Keep the module and seed useful
    # structural placeholders without pretending they came from
    # the target architecture.
    # ========================================================

    "outcome/controller": [
        "OutcomeController.java",
    ],

    "outcome/dto": [
        "OutcomeResponse.java",
        "CreateOutcomeRequest.java",
    ],

    "outcome/enums": [
        "OutcomeStatus.java",
    ],

    "outcome/model": [
        "OutcomeRecord.java",
    ],

    "outcome/repository": [
        "OutcomeRepository.java",
    ],

    "outcome/service": [
        "OutcomeService.java",
    ],


    # ========================================================
    # INVESTIGATION - CURRENT MODULE
    # ========================================================

    "investigation/controller": [
        "InvestigationController.java",
    ],

    "investigation/dto": [
        "InvestigationResponse.java",
        "CreateInvestigationRequest.java",
    ],

    "investigation/enums": [
        "InvestigationStatus.java",
    ],

    "investigation/model": [
        "Investigation.java",
    ],

    "investigation/repository": [
        "InvestigationRepository.java",
    ],

    "investigation/service": [
        "InvestigationService.java",
    ],


    # ========================================================
    # AI
    # ========================================================

    "ai/controller": [
        "ExplanationController.java",
        "ContractInterpretationController.java",
    ],

    "ai/dto": [
        "ExplainOpportunityRequest.java",
        "ExplanationResponse.java",
        "InterpretContractRequest.java",
        "ContractInterpretationResponse.java",
    ],

    "ai/model": [
        "AiAnalysis.java",
        "AiExplanation.java",
        "ExtractedCommercialTerm.java",
    ],

    "ai/enums": [
        "AiTaskType.java",
        "AiProcessingStatus.java",
        "AiValidationStatus.java",
    ],

    "ai/client": [
        "LlmClient.java",
        "EmbeddingClient.java",
        "DocumentExtractionClient.java",
    ],

    "ai/service": [
        "AiExplanationService.java",
        "ContractInterpretationService.java",
        "AiGuardrailService.java",
        "AiContextService.java",
    ],

    "ai/extraction": [
        "ContractExtractionService.java",
        "StructuredExtractionValidator.java",
        "ExtractionResult.java",
    ],

    # Existing AI PDF directory preserved.
    "ai/pdf": [
        "PdfExtractionService.java",
        "AiPdfService.java",
    ],


    # ========================================================
    # REPORTING
    # ========================================================

    "reporting/controller": [
        "ReportController.java",
    ],

    "reporting/dto": [
        "GenerateOpportunityReportRequest.java",
        "ReportResponse.java",
    ],

    "reporting/model": [
        "Report.java",
        "ReportArtifact.java",
    ],

    "reporting/enums": [
        "ReportType.java",
    ],

    "reporting/service": [
        "ReportService.java",
        "OpportunityReportService.java",
    ],

    "reporting/pdf": [
        "PdfReportGenerator.java",
        "PdfTemplateService.java",
    ],


    # ========================================================
    # PROCESSING
    # ========================================================

    "processing/ingestion": [
        "IngestionJobConfiguration.java",
        "IngestionJobLauncher.java",
        "IngestionProcessor.java",
        "IngestionWriter.java",
    ],

    "processing/financialtruth": [
        "CalculationJobConfiguration.java",
        "CalculationJobLauncher.java",
        "CalculationProcessor.java",
        "CalculationWriter.java",
    ],

    "processing/reporting": [
        "ReportJobConfiguration.java",
        "ReportJobLauncher.java",
    ],

    "processing/common": [
        "JobExecutionService.java",
        "JobFailureHandler.java",
    ],
}


# ============================================================
# TEST FILE MAP
# ============================================================

TEST_FILES = {
    "architecture": [
        "ModuleBoundaryTest.java",
        "DependencyRuleTest.java",
    ],

    "security": [
        "AuthenticationTest.java",
        "AuthorizationTest.java",
        "TenantIsolationTest.java",
        "FileUploadSecurityTest.java",
    ],

    "identity": [
        "IdentityServiceTest.java",
        "TenantAccessServiceTest.java",
    ],

    "ingestion": [
        "CsvFileParserTest.java",
        "ExcelFileParserTest.java",
        "FileValidationServiceTest.java",
        "IngestionServiceTest.java",
    ],

    "financial": [
        "InvoiceNormalizationTest.java",
        "FinancialDataServiceTest.java",
    ],

    "contract": [
        "ContractServiceTest.java",
        "CommercialRuleServiceTest.java",
    ],

    "financialtruth": [
        "MoneyTest.java",
        "PricingVarianceRuleTest.java",
        "DiscountVarianceRuleTest.java",
        "FinancialTruthEngineTest.java",
        "CalculationReproducibilityTest.java",
        "FinancialRegressionTest.java",
    ],

    "evidence": [
        "EvidenceServiceTest.java",
        "LineageServiceTest.java",
    ],

    "opportunity": [
        "OpportunityDetectionTest.java",
        "OpportunityValidationTest.java",
        "OpportunityLifecycleTest.java",
    ],

    "value": [
        "ActionServiceTest.java",
        "OutcomeServiceTest.java",
        "ValueAttributionTest.java",
    ],

    "outcome": [
        "OutcomeServiceTest.java",
    ],

    "api": [
        "IngestionControllerTest.java",
        "CalculationControllerTest.java",
        "OpportunityControllerTest.java",
    ],
}


# ============================================================
# NON-JAVA FILES
# ============================================================

RESOURCE_FILES = [
    # Configuration
    "src/main/resources/application.yml",
    "src/main/resources/application-dev.yml",
    "src/main/resources/application-test.yml",
    "src/main/resources/application-prod.yml",
    "src/main/resources/logback-spring.xml",

    # Flyway migrations
    "src/main/resources/db/migration/V1__create_organizations.sql",
    "src/main/resources/db/migration/V2__create_users_roles.sql",
    "src/main/resources/db/migration/V3__create_ingestion.sql",
    "src/main/resources/db/migration/V4__create_financial_data.sql",
    "src/main/resources/db/migration/V5__create_contracts.sql",
    "src/main/resources/db/migration/V6__create_calculations.sql",
    "src/main/resources/db/migration/V7__create_evidence_lineage.sql",
    "src/main/resources/db/migration/V8__create_opportunities.sql",
    "src/main/resources/db/migration/V9__create_value_tracking.sql",
    "src/main/resources/db/migration/V10__create_audit.sql",

    # AI prompts
    "src/main/resources/prompts/opportunity-explanation.txt",
    "src/main/resources/prompts/contract-term-extraction.txt",
]


DOC_FILES = [
    "docs/architecture/system-architecture.md",
    "docs/architecture/module-boundaries.md",
    "docs/architecture/phase-0-scope.md",
    "docs/architecture/financial-truth.md",
    "docs/architecture/security-model.md",

    "docs/decisions/ADR-001-modular-monolith.md",
    "docs/decisions/ADR-002-financial-truth-over-ai.md",
    "docs/decisions/ADR-003-economic-opportunity-record.md",
    "docs/decisions/ADR-004-source-data-lineage.md",
]


# ============================================================
# CONTENT GENERATORS
# ============================================================

def package_from_java_path(path: Path) -> str:
    rel = path.relative_to(JAVA_ROOT)
    parts = list(rel.parts)

    # Remove the filename.
    package_parts = parts[:-1]

    package = "com.fintech.cfo"
    if package_parts:
        package += "." + ".".join(package_parts)

    return package


def java_placeholder(path: Path) -> str:
    class_name = path.stem
    package = package_from_java_path(path)

    return f"""package {package};

/**
 * TODO: Implement {class_name}.
 *
 * Architecture placeholder generated by create_cfo_architecture_mega.py.
 * This file intentionally contains no business logic.
 */
public class {class_name} {{
    // TODO: Implement {class_name}.
}}
"""


def test_package_from_path(path: Path) -> str:
    rel = path.relative_to(TEST_ROOT)
    parts = list(rel.parts)
    package_parts = parts[:-1]

    package = "com.fintech.cfo"
    if package_parts:
        package += "." + ".".join(package_parts)

    return package


def test_placeholder(path: Path) -> str:
    class_name = path.stem
    package = test_package_from_path(path)

    return f"""package {package};

/**
 * TODO: Implement {class_name}.
 *
 * Test placeholder generated by create_cfo_architecture_mega.py.
 */
public class {class_name} {{
    // TODO: Add test cases.
}}
"""


def generic_placeholder(path: Path) -> str:
    suffix = path.suffix.lower()

    if suffix == ".yml":
        return f"""# {path.name}
# CFO configuration placeholder.
# TODO: Add the environment-specific configuration.
"""

    if suffix == ".xml":
        return """<?xml version="1.0" encoding="UTF-8"?>
<!--
    CFO logging configuration placeholder.
    TODO: Add the actual Logback configuration.
-->
"""

    if suffix == ".sql":
        return f"""-- ============================================================
-- {path.name}
-- ============================================================
-- Migration placeholder.
-- TODO: Add the actual schema migration.
-- ============================================================
"""

    if suffix == ".txt":
        return f"""# {path.name}

TODO: Add prompt/template content.
"""

    if suffix == ".md":
        title = path.stem.replace("-", " ").replace("_", " ").title()
        return f"""# {title}

TODO: Add documentation content.
"""

    return f"// TODO: Implement {path.name}\n"


# ============================================================
# SAFE FILE CREATION
# ============================================================

def create_file(path: Path, content: str, stats: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)

    if path.exists():
        stats["existing"] += 1
        stats["existing_paths"].append(path)
        return

    try:
        path.write_text(content, encoding="utf-8", newline="\n")
        stats["created"] += 1
        stats["created_paths"].append(path)
    except Exception as exc:
        stats["errors"] += 1
        stats["errors_details"].append((path, exc))


# ============================================================
# MAIN
# ============================================================

def main() -> int:
    stats = {
        "directories_created": 0,
        "created": 0,
        "existing": 0,
        "errors": 0,
        "created_paths": [],
        "existing_paths": [],
        "errors_details": [],
    }

    print()
    print("=" * 78)
    print("CFO MEGA ARCHITECTURE SCAFFOLDER")
    print("=" * 78)
    print(f"Project root : {PROJECT_ROOT}")
    print(f"Java root    : {JAVA_ROOT}")
    print()

    # --------------------------------------------------------
    # 1. Folders
    # --------------------------------------------------------

    print("[1/4] Creating/checking architecture folders...")

    for relative_dir in DIRECTORIES:
        directory = PROJECT_ROOT / relative_dir

        try:
            existed = directory.exists()
            directory.mkdir(parents=True, exist_ok=True)

            if not existed:
                stats["directories_created"] += 1

        except Exception as exc:
            stats["errors"] += 1
            stats["errors_details"].append((directory, exc))

    print(f"      New folders created: {stats['directories_created']}")

    # --------------------------------------------------------
    # 2. Main application placeholder
    # --------------------------------------------------------

    print("[2/4] Creating/checking main Java files...")

    app_file = JAVA_ROOT / "CfoApplication.java"

    create_file(
        app_file,
        """package com.fintech.cfo;

/**
 * Main Spring Boot application entry point.
 *
 * IMPORTANT:
 * If this file already existed, the scaffolder leaves the existing
 * implementation untouched.
 */
public class CfoApplication {
    // TODO: Preserve/use the actual Spring Boot application implementation.
}
""",
        stats,
    )

    # Main architecture files.
    for package_dir, filenames in JAVA_FILES.items():
        directory = JAVA_ROOT / package_dir
        directory.mkdir(parents=True, exist_ok=True)

        for filename in filenames:
            path = directory / filename
            create_file(path, java_placeholder(path), stats)

    # --------------------------------------------------------
    # 3. Test files
    # --------------------------------------------------------

    print("[3/4] Creating/checking test placeholders...")

    for package_dir, filenames in TEST_FILES.items():
        directory = TEST_ROOT / package_dir
        directory.mkdir(parents=True, exist_ok=True)

        for filename in filenames:
            path = directory / filename
            create_file(path, test_placeholder(path), stats)

    # --------------------------------------------------------
    # 4. Resources + docs
    # --------------------------------------------------------

    print("[4/4] Creating/checking resources, SQL, prompts and docs...")

    for relative_path in RESOURCE_FILES:
        path = PROJECT_ROOT / relative_path
        create_file(path, generic_placeholder(path), stats)

    for relative_path in DOC_FILES:
        path = PROJECT_ROOT / relative_path
        create_file(path, generic_placeholder(path), stats)

    # --------------------------------------------------------
    # Final result
    # --------------------------------------------------------

    print()
    print("=" * 78)
    print("SCAFFOLDING FINISHED")
    print("=" * 78)
    print(f"New folders created : {stats['directories_created']}")
    print(f"New files created   : {stats['created']}")
    print(f"Existing files kept : {stats['existing']}")
    print(f"Errors              : {stats['errors']}")
    print()

    if stats["created"]:
        print("Files created:")
        for path in stats["created_paths"]:
            try:
                print("  +", path.relative_to(PROJECT_ROOT))
            except ValueError:
                print("  +", path)
        print()

    if stats["existing"]:
        print("Existing files were NOT overwritten.")
        print()

    if stats["errors"]:
        print("Errors encountered:")
        for path, exc in stats["errors_details"]:
            print(f"  ! {path}: {exc}")
        print()
        print("The script completed, but the error list above should be checked.")
        return 1

    print("SAFE TO RUN AGAIN.")
    print()
    print("Verify the complete structure with:")
    print("    tree /f")
    print()

    return 0


if __name__ == "__main__":
    sys.exit(main())
