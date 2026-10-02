## 2. Identity, tenancy & authorization

### Module goal

Every business operation in this system must know **who** is performing it and
**which organization (tenant)** they belong to. The identity module provides
that knowledge: model (User, Organization, Membership, Role, Permission),
authentication plumbing (JWT conversion, Spring Security configuration,
tenant-context filter), and authorization checks (`TenantAccessService`,
`AuthorizationService`). It is the gateway that every downstream module
(ingestion, financial, contract, reporting, etc.) depends on for tenant-scoped,
auditable operations.

### Implementation status

This is a **designed-but-unimplemented milestone**, per the vertical-slice
ordering in `docs/architecture/flow of files` (Phase 2: "Identity + tenant
security"). All 30 files under `identity/` are generator-stub placeholders —
package declaration, a `TODO: Implement X` Javadoc block, and an empty class
body. No business logic, no signatures, no annotations exist yet.

The **tenancy and security primitives live in `shared/` and ARE implemented**:

| Layer | File | Status |
|---|---|---|
| `shared/domain` | `TenantId.java` | Implemented |
| `shared/domain` | `UserId.java` | Implemented |
| `shared/domain` | `OrganizationId.java` | Implemented |
| `shared/domain` | `SourceReference.java` | Implemented |
| `shared/security` | `SecurityPrincipal.java` | Implemented |
| `shared/security` | `SecurityContext.java` | Implemented |
| `shared/exception` | `AccessDeniedException.java` | Implemented (out of slice, referenced) |

The split is plain: the identity module is scaffolding; the shared primitives
it will build on are real. Until identity is implemented, no request can be
authenticated or tenant-scoped, so integration with downstream modules cannot
begin.

### File inventory

#### identity/security/ (7 files — commented this pass; code is planned, not written)

| File | Goal of this file |
|---|---|
| `CurrentUser.java` | Injection point that surfaces the authenticated `SecurityPrincipal` to controllers and services. |
| `SecurityConfig.java` | Spring Security configuration: registers the JWT converter, tenant-context filter, and authorization rules in the correct order. |
| `JwtAuthenticationConverter.java` | Converts a verified JWT into a Spring `Authentication` carrying a `SecurityPrincipal`; must not derive tenant scope from the token body. |
| `CurrentUserProvider.java` | Fail-fast accessor that resolves the `SecurityPrincipal` from `SecurityContext`, throwing `AccessDeniedException` when absent. |
| `TenantContext.java` | Request-scoped holder for the resolved `OrganizationId`, backed by a `ScopedValue` (not `ThreadLocal`). |
| `SecurityHeadersConfig.java` | Configures HTTP response security headers (CSP, HSTS, X-Frame-Options) as defense-in-depth. |
| `TenantContextFilter.java` | Servlet filter that binds the tenant scope via `ScopedValue` around the downstream chain, reading tenant only from the verified principal. |

#### identity/service/ (4 files — commented this pass; code is planned, not written)

| File | Goal of this file |
|---|---|
| `UserService.java` | Application service for user lifecycle, always scoped to the caller's organization. |
| `TenantAccessService.java` | THE authority check enforcing §6: verifies the principal belongs to the requested organization. |
| `OrganizationService.java` | Application service for organization provisioning, strictly scoped to the caller's own tenant. |
| `AuthorizationService.java` | Business-layer authorization: checks the principal holds the required authority for the caller's organization. |

#### identity/model/ (5 files — commented this pass; code is planned, not written)

| File | Goal of this file |
|---|---|
| `Membership.java` | Domain model linking a `User` to an `Organization` with a `Role`; the record that establishes tenant membership. |
| `Organization.java` | Domain model and root aggregate for the tenant boundary (`organization_id` from §6). |
| `Permission.java` | Domain model: a named authorization right, always scoped to one organization. |
| `Role.java` | Domain model: a named collection of `Permission`s, scoped to one organization. |
| `User.java` | Domain model: a person who authenticates; identity is carried in `SecurityPrincipal`. |

#### identity/enums/ (3 files — commented this pass; code is planned, not written)

| File | Goal of this file |
|---|---|
| `UserStatus.java` | Closed set of user lifecycle states that gate whether authentication succeeds. |
| `RoleType.java` | Closed vocabulary of tenant-scoped role categories (OWNER, ADMIN, MEMBER). |
| `PermissionType.java` | Closed vocabulary of permission types that drive authority strings and `AuthorizationService` checks. |

#### identity/dto/ (4 files — commented this pass; code is planned, not written)

| File | Goal of this file |
|---|---|
| `OrganizationResponse.java` | API-safe DTO projected from `Organization` for HTTP responses. |
| `PermissionResponse.java` | API-safe DTO projected from `Permission` for HTTP responses. |
| `RoleResponse.java` | API-safe DTO projected from `Role` for HTTP responses. |
| `UserResponse.java` | API-safe DTO projected from `User` for HTTP responses. |

#### identity/controller/ (3 files — left byte-identical per §11)

| File | Goal of this file |
|---|---|
| `UserController.java` | Planned REST controller for user management operations. |
| `RoleController.java` | Planned REST controller for role management operations. |
| `OrganizationController.java` | Planned REST controller for organization management operations. |

#### identity/repository/ (4 files — left byte-identical per §11)

| File | Goal of this file |
|---|---|
| `UserRepository.java` | Planned persistence port for `User` records, scoped to `organization_id` on every query. |
| `RoleRepository.java` | Planned persistence port for `Role` records, scoped to `organization_id`. |
| `OrganizationRepository.java` | Planned persistence port for `Organization` records. |
| `MembershipRepository.java` | Planned persistence port for `Membership` records, the authoritative tenant link. |

#### shared/security/ (2 files — implemented; Javadoc enhanced this pass)

| File | Goal of this file |
|---|---|
| `SecurityPrincipal.java` | Immutable record carrying `userId`, `email`, `organizationId`, `authorities`, and `tenantVerified`. |
| `SecurityContext.java` | Static holder that reads the `SecurityPrincipal` from Spring Security's `SecurityContextHolder`. |

#### shared/domain/ (4 files — implemented; Javadoc enhanced this pass)

| File | Goal of this file |
|---|---|
| `TenantId.java` | Strongly typed, immutable tenant identifier; identifier only, not an authorization decision. |
| `UserId.java` | Strongly typed, immutable user identifier; prevents cross-type confusion at compile time. |
| `OrganizationId.java` | Strongly typed, immutable organization identifier; the tenant boundary from §6. |
| `SourceReference.java` | Immutable pointer from normalized data back to its origin file/row for lineage (§5). |

### Flow of journey (runtime request path)

```
JWT  →  JwtAuthenticationConverter  →  SecurityContext  →  TenantContextFilter
     →  ScopedValue (TenantContext)  →  AuthorizationService/TenantAccessService
     →  Service  →  Repository
```

1. **Incoming JWT** is verified by Spring Security's filter chain.
   - *IMPLEMENTED* (Spring Security standard) — *CONVERTER PLANNED*
2. **`JwtAuthenticationConverter`** converts the verified `Jwt` into an
   `Authentication` carrying a `SecurityPrincipal`, extracting userId, email,
   and authority claims — but **not** organization scope from the token body.
   - *PLANNED*
3. **`SecurityContext`** holds the authenticated `SecurityPrincipal` as the
   single source of truth for request identity, reading from Spring Security's
   `SecurityContextHolder`.
   - *IMPLEMENTED*
4. **`TenantContextFilter`** runs after authentication, reads the organization
   from the verified `SecurityPrincipal`, and binds it via `ScopedValue` through
   `TenantContext` so the entire downstream chain observes one tenant.
   - *PLANNED*
5. **ScopedValue tenant context** (`TenantContext`) makes the `OrganizationId`
   available to all downstream calls within the same request scope, replacing
   what would otherwise be a `ThreadLocal` (immutable, lexically scoped,
   auto-unbound — §1).
   - *PLANNED*
6. **`AuthorizationService` / `TenantAccessService`** check that the principal
   is tenant-verified and holds the required authority for the caller's
   organization, throwing `AccessDeniedException` on failure.
   - *PLANNED*
7. **Service** (e.g. `UserService`, `OrganizationService`) performs business
   logic, always filtering queries on the principal's organization (§6).
   - *PLANNED*
8. **Repository** (e.g. `UserRepository`) persists/retrieves records, scoped to
   `organization_id` on every query (§6).
   - *PLANNED* (left untouched per §11)

### Flow of implementation

**Tenancy model.** `organization_id` is the tenant boundary (§6). Every
tenant-owned table will carry it and every query will filter on it. The
identifier types are the implemented shared primitives: `OrganizationId`
(the boundary), `TenantId` (identifier-only, never an authorization
decision), and `UserId` (strongly typed to prevent cross-type confusion).
The identity model layer will wire these into `Organization`, `User`,
`Membership`, `Role`, and `Permission`, each scoped to exactly one
organization.

**Security-principal contract.** `SecurityPrincipal` is the single record of
who the caller is and which organizations they may act for. It is immutable
(a record), established server-side from a **verified** JWT, and its
`organizationId` is set only after a server-side `Membership` check — never
accepted from a client request body or query parameter (§6). The
`tenantVerified` flag is the gate: `isTenantResolved()` returns true only
when both `organizationId` is present **and** `tenantVerified` is true.
`AccessDeniedException` is the thrown signal when a principal is missing or
unauthorized.

**`ScopedValue` vs `ThreadLocal`.** `SecurityContext` (implemented) delegates
to Spring Security's `SecurityContextHolder` — the canonical request-identity
holder populated by Spring's filter chain. `TenantContext` /
`TenantContextFilter` (planned) will bridge from that principal into a
`ScopedValue`-bound tenant scope (§1): `ScopedValue` is immutable, lexically
scoped, and automatically unbound on exit, so a tenant value cannot leak
into a pooled thread. This is why tenant scope lives in `identity/security`,
separate from identity in `shared/security`.

**Duplication risk.** There is a deliberate, documented overlap between
`identity/security/` stubs and `shared/security/`:

- `identity/security/{CurrentUser, CurrentUserProvider}` — planned injection
  points — will read from `shared/security/SecurityContext`, which already
  holds `shared/security/SecurityPrincipal`.
- `identity/security/{TenantContext, TenantContextFilter}` — planned
  `ScopedValue` binding — wraps the organization from
  `SecurityPrincipal.organizationId()`.

The risk is that these planned types could duplicate the principal-holding
role that `SecurityContext` already fills. The boundary is: `SecurityContext`
owns **who**, `TenantContext` owns **which tenant**. They must not be
collapsed into one type, or the lexical scope of `ScopedValue` (needed for
leak prevention) would be lost. This is recorded in the class-level comments
on both sides.

**Invariants from §6 (restated in the comments above):**

1. `organization_id` is the tenant boundary; every query filters on it.
2. Organization id is **never** accepted from a request body or query
   parameter — it comes from the authenticated `SecurityPrincipal` via
   `SecurityContext`.
3. A record not found within the caller's tenant returns not-found; never
   distinguish "absent" from "belongs to someone else".
4. Never log monetary values, credentials, or bearer tokens — only
   identifiers, statuses, and correlation IDs.

### Key comments added

This pass added only **comments** — no executable code, no imports, no
signatures, no annotations, no indentation changes.

**Identity stubs commented (22 files):** one class-level Javadoc was prepended
above each existing `TODO` block in:

- `identity/security/`: `CurrentUser`, `SecurityConfig`,
  `JwtAuthenticationConverter`, `CurrentUserProvider`, `TenantContext`,
  `SecurityHeadersConfig`, `TenantContextFilter` — each states its role in the
  authn/authz chain, the §6 invariants it must honour, and intended
  collaborators.
- `identity/service/`: `UserService`, `TenantAccessService` (the §6 gate),
  `OrganizationService`, `AuthorizationService`.
- `identity/model/`: `Membership`, `Organization`, `Permission`, `Role`,
  `User`.
- `identity/enums/`: `UserStatus`, `RoleType`, `PermissionType`.
- `identity/dto/`: `OrganizationResponse`, `PermissionResponse`,
  `RoleResponse`, `UserResponse`.

**Identity stubs left byte-identical (7 files):** all files under
`identity/controller/` and `identity/repository/` per §11 — verified
unchanged.

**Shared files enhanced (3 files):**

- `shared/security/SecurityContext.java` — added WHY `ScopedValue` is not
  used directly (Spring Security populates the holder; the identity layer
  bridges via `TenantContextFilter`).
- `shared/domain/TenantId.java` — added WHY immutable record (thread safety
  across pooled threads, determinism per §4).
- `shared/domain/UserId.java` — expanded to explain WHY strongly typed and
  immutable (compile-time type safety, leak prevention across tenants).
