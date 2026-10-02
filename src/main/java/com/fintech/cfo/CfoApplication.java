package com.fintech.cfo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Application entry point and Spring Boot configuration root.
 *
 * <p>Three things happen because of the two annotations on this class:
 *
 * <ol>
 * <li>{@link SpringBootApplication} turns on auto-configuration and component
 * scanning. Because this class sits in {@code com.fintech.cfo}, the scan root is
 * that package, so every {@code @Component}, {@code @Service},
 * {@code @Repository} and {@code @RestController} beneath it is discovered
 * automatically. Nothing needs an explicit bean definition.</li>
 * <li>{@link SpringBootApplication} also embeds {@code @EnableAutoConfiguration},
 * which is what wires the infrastructure the modules depend on: the DataSource,
 * JPA repositories, the Flyway migration runner and the Spring Security filter
 * chain. Auto-configuration is conditional — a bean is only contributed if the
 * classes it needs are on the classpath.</li>
 * <li>{@link ConfigurationPropertiesScan} registers every
 * {@code @ConfigurationProperties} class in this package tree, which is how
 * {@code ApplicationProperties} and the {@code application.yml} /
 * {@code application-{profile}.yml} values are bound to typed Java fields rather
 * than being read from the environment ad hoc.</li>
 * </ol>
 *
 * <p>Startup order that follows from this: Flyway applies the
 * {@code V1..Vn} migrations under {@code src/main/resources/db/migration} before
 * the JPA {@code EntityManagerFactory} is validated against the resulting schema,
 * so entities such as {@code AuditEventEntity} must match the migrated DDL
 * exactly.
 *
 * <p>Intentionally empty of behaviour. A {@code main} method plus two
 * annotations is the whole contract; any logic added here would run before the
 * application context exists and could not participate in dependency injection.
 */
/**
 * Bootstrap entry point for the AI_CFO modular monolith.
 *
 * <p>The component scan root is this package, so every module under
 * {@code com.fintech.cfo.*} is discovered from here while module boundaries
 * remain visible in the package layout. This class intentionally holds no
 * business logic: it exists only to trigger Spring Boot auto-configuration, and
 * every capability it enables is defined in {@code platform/config}.
 *
 * <h2>Why both annotations</h2>
 * <ul>
 * <li>{@code @SpringBootApplication} supplies auto-configuration, component
 * scanning from this package, and the auto-configured web application type.</li>
 * <li>{@code @ConfigurationPropertiesScan} binds the {@code @ConfigurationProperties}
 * records declared under this package — chiefly
 * {@code platform.config.ApplicationProperties} — so a module can declare a
 * typed configuration class and have it registered by being present, without a
 * separate {@code @EnableConfigurationProperties} and without any module having
 * to remember to register another's properties.</li>
 * </ul>
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class CfoApplication {

	/**
	 * Boots the application.
	 *
	 * <p>{@code args} is what the JVM passes after the {@code -jar} and any
	 * {@code --key=value} pairs, so the launch profile and any overrides reach
	 * Spring's {@code Environment}. {@code SpringApplication.run} returns a
	 * {@code ConfigurableApplicationContext} that is deliberately discarded: in a
	 * servlet application the context is held by the embedded server for the
	 * lifetime of the process, and keeping a static reference would only risk a
	 * premature shutdown hook firing while requests are still in flight.
	 *
	 * <p>Any failure during context refresh (bad configuration, failed migration,
	 * unresolvable bean) throws here and terminates the JVM, which is the intended
	 * behaviour: a partially started instance must never accept traffic.
	 */
	/**
	 * Starts the application context and the embedded server.
	 *
	 * @param args command-line arguments, merged beneath
	 *             {@code application.*} configuration properties
	 */
	public static void main(String[] args) {
		// Returns only when startup fails; the running context then owns the JVM lifetime.
		SpringApplication.run(CfoApplication.class, args);
	}

}
