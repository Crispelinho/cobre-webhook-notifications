plugins {
	java
	id("org.springframework.boot") version "4.1.2"
	id("io.spring.dependency-management") version "1.1.7"
}

group = "com.cobre"
version = "0.0.1-SNAPSHOT"
description = "Webhook event notification delivery and self-service API"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(25)
	}
}

repositories {
	mavenCentral()
}

// Development-only tools, kept out of the application artifact.
val devTools by sourceSets.creating {
	java.srcDir("tools/dev-token/src/main/java")
}

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	implementation("org.springframework.boot:spring-boot-starter-flyway")
	implementation("org.springframework.boot:spring-boot-starter-kafka")
	implementation("org.springframework.boot:spring-boot-starter-opentelemetry")
	implementation("org.springframework.boot:spring-boot-starter-security")
	implementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	implementation("org.flywaydb:flyway-database-postgresql")
	developmentOnly("org.springframework.boot:spring-boot-docker-compose")
	runtimeOnly("org.postgresql:postgresql")
	testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
	testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
	testImplementation("org.springframework.boot:spring-boot-starter-flyway-test")
	testImplementation("org.springframework.boot:spring-boot-starter-kafka-test")
	testImplementation("org.springframework.boot:spring-boot-starter-opentelemetry-test")
	testImplementation("org.springframework.boot:spring-boot-starter-security-oauth2-resource-server-test")
	testImplementation("org.springframework.boot:spring-boot-starter-security-test")
	testImplementation("org.springframework.boot:spring-boot-starter-validation-test")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testImplementation("org.springframework.boot:spring-boot-testcontainers")
	testImplementation("org.testcontainers:testcontainers-grafana")
	testImplementation("org.testcontainers:testcontainers-junit-jupiter")
	testImplementation("org.testcontainers:testcontainers-kafka")
	testImplementation("org.testcontainers:testcontainers-postgresql")
	// Shaded Jetty: no clash with the Tomcat of the application under test.
	testImplementation("org.wiremock:wiremock-standalone:3.13.2")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
	// Same version that spring-security-oauth2-jose brings to the application.
	"devToolsImplementation"("com.nimbusds:nimbus-jose-jwt:10.9.1")
	// Lets a test prove that the application accepts dev tokens.
	testImplementation(devTools.output)

	constraints {
		implementation("at.yawk.lz4:lz4-java:1.11.4") {
			because("GHSA-mcr4-qmvw-px4g (high): versions <= 1.11.3 are vulnerable; pulled transitively by kafka-clients")
		}
		implementation("org.xerial.snappy:snappy-java:1.1.10.12") {
			because("GHSA-q233-g92q-qxp6 and GHSA-gwq9-9ffj-mvrv (high): versions <= 1.1.10.8 are vulnerable; pulled transitively by kafka-clients")
		}
	}
}

tasks.withType<Test> {
	useJUnitPlatform()
	// Tests start no LGTM stack (see LgtmContainerConfiguration), so OTLP export has nowhere to go.
	systemProperty("management.otlp.metrics.export.enabled", "false")
	systemProperty("management.tracing.export.otlp.enabled", "false")
	systemProperty("management.logging.export.otlp.enabled", "false")
}

tasks.register<JavaExec>("devToken") {
	group = "application"
	description = "Prints a development-only JWT. Usage: ./gradlew devToken --args=\"CLIENT001 notifications:read\""
	classpath = devTools.runtimeClasspath
	mainClass = "com.cobre.notifications.devtools.DevTokenTool"
	workingDir = rootDir
}
