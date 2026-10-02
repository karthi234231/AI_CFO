package com.fintech.cfo;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * The canary that the application context starts at all.
 *
 * <p><b>Why a context that asserts nothing still matters.</b> Almost every defect
 * this test catches is invisible at compile time: a bean with two constructors and
 * no {@code @Primary}, a missing Flyway migration for a mapped entity, a
 * property-binding typo, a circular dependency introduced by a new module. Each of
 * those breaks wiring, and each would otherwise be discovered by hand in a running
 * system rather than in the build. An empty body is therefore the point: the
 * assertion is that {@code @SpringBootTest} can get as far as running the method.
 *
 * <p>Two consequences follow from the annotations. {@code @SpringBootTest} without
 * a {@code webEnvironment} starts a non-web context, which is the cheapest way to
 * prove the wiring. {@code @Import} adds the container configuration explicitly
 * rather than relying on component scanning, because {@code @TestConfiguration}
 * classes are excluded from scanning by design.
 *
 * <p><b>Prerequisite.</b> This test cannot run without Docker: it starts a
 * PostgreSQL container and applies every Flyway migration against it. Where there
 * is no daemon, it fails during setup and proves nothing - so it must never be
 * reported as a passing test in an environment that could not run it.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class CfoApplicationTests {

	@Test
	void contextLoads() {
		// Intentionally empty. The subject is the context starting, not this method.
	}

}
