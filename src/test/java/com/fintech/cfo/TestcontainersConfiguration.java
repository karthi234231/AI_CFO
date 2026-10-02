package com.fintech.cfo;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Test-only wiring for the PostgreSQL container the integration tests run against.
 *
 * <p><b>Why this is a {@code @TestConfiguration} and not a bean in the
 * application.</b> The production application must never start a container. This
 * configuration is imported explicitly - by {@code CfoApplicationTests} and by any
 * integration test that needs the schema - and the {@link ServiceConnection}
 * annotation hands Boot the container's JDBC url, driver and credentials so tests
 * never hardcode a connection string.
 *
 * <p><b>Why the image is pinned to a major version.</b> This used to be the floating
 * tag {@code postgres:latest} that the Boot initialiser generates, and
 * {@code HELP.md} flagged it for pinning before the build left a developer machine:
 * a floating tag means the schema under test can change under you, because a new
 * minor release can alter default authentication, collation or {@code TimeZone}
 * validation and silently turn a green build red - or, worse, a red assertion green.
 * The tag is now the explicit {@code postgres:17}, matching the PostgreSQL major
 * version the production deployment runs, so a fresh Docker pull cannot move the
 * database the tests validate migrations against. It must be bumped deliberately,
 * alongside the production version, never by a pull.
 *
 * <p><b>What pinning the image does not fix.</b> It is deliberately paired with
 * {@code -Duser.timezone=UTC} on the surefire {@code argLine} in {@code pom.xml}.
 * Both {@code postgres:17} and {@code postgres:18} reject the legacy
 * {@code Asia/Calcutta} alias that a JDK 25 running on an "India Standard Time"
 * host reports, so the host timezone has to be taken out of the equation on the
 * JVM side; pinning alone is not sufficient.
 *
 * <p><b>Prerequisite.</b> A running Docker daemon. Where there is none, every
 * test that imports this cannot execute at all - it fails at container start, not
 * at an assertion - which is why container-backed tests are reported separately
 * from the unit suite rather than counted as passing.
 */
@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

	@Bean
	@ServiceConnection
	PostgreSQLContainer postgresContainer() {
		// Pinned to the PostgreSQL major version production runs, so the schema the
		// Flyway migrations are validated against cannot move under a test run.
		return new PostgreSQLContainer(DockerImageName.parse("postgres:17"));
	}

}
