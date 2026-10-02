package com.fintech.cfo.platform.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.parameters.HeaderParameter;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;

/**
 * OpenAPI documentation for the REST API.
 *
 * <p>Internal implementation classes, database credentials, secret
 * configuration and internal actuator details are never documented.
 *
 * <p><b>What this bean is and is not.</b> It supplies the single document-level
 * description that applies to every endpoint: the service title, the
 * authentication scheme, and the correlation header. Per-endpoint documentation is
 * declared on the controllers themselves and merged into this one document; nothing
 * here describes a specific operation, so a new module's endpoints are documented by
 * being written, not by being registered here.
 *
 * <p><b>Why the correlation header is documented at all.</b> It is optional on the
 * request, but a client that sends one gets its ID honoured across logs, and a
 * client that does not still receives one back. Declaring it with the same length
 * and character restrictions that {@code CorrelationIdFilter} enforces is what
 * stops a generated client from sending a value the filter will silently discard.
 *
 * <p><b>Why the description is explicit about branching on {@code code}.</b> Clients
 * written against {@code detail} break the first time a message is reworded. The
 * stable code is the contract; this text says so where an integrator will read it.
 */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

	/** Scheme name; referenced by both the definition and the global requirement. */
	private static final String BEARER_SCHEME = "bearerAuth";

	/** Shared header name, matching {@code CorrelationIdFilter.HEADER_NAME}. */
	private static final String CORRELATION_HEADER = "X-Correlation-ID";

	/**
	 * @return the document-level OpenAPI model for the Phase 0 API
	 */
	@Bean
	public OpenAPI openAPI() {
		return new OpenAPI()
				.info(new Info()
						.title("Enterprise Financial & Profit Intelligence Infrastructure")
						.version("v1")
						.description("""
								Phase 0 REST API.

								The platform ingests controlled financial data, establishes deterministic and \
								auditable financial truth, and reports evidence-backed economic opportunities \
								that a finance team can independently verify and act upon.

								Every response carries an X-Correlation-ID header. Errors use a stable \
								machine-readable 'code'; clients must depend on 'code' rather than 'detail'.
								"""))
				.components(new Components()
						// Declared as HTTP bearer rather than an OAuth2 flow because the
						// identity provider is external: this service only validates the
						// token it is handed.
						.addSecuritySchemes(BEARER_SCHEME, new SecurityScheme()
								.type(SecurityScheme.Type.HTTP)
								.scheme("bearer")
								.bearerFormat("JWT")
								.description("OIDC access token"))
						.addParameters(CORRELATION_HEADER, new HeaderParameter()
								.name(CORRELATION_HEADER)
								.description("Request correlation identifier (max 64 chars, [A-Za-z0-9._-]).")
								.required(false)))
				// Applied globally, so a newly added endpoint is authenticated by
				// default rather than becoming the one endpoint that forgot to be.
				.addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
	}

}
