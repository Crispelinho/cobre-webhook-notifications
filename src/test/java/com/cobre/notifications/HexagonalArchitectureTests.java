package com.cobre.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Keeps the inner hexagon framework-free: domain and application code must not depend on Spring, JPA or adapters.
 */
class HexagonalArchitectureTests {

	private static final Path SOURCES = Path.of("src/main/java/com/cobre/notifications");

	private static final List<String> FORBIDDEN_IMPORTS = List.of("import org.springframework.", "import jakarta.persistence.",
			"import com.cobre.notifications.shared.", ".adapters.");

	@Test
	void domainAndApplicationLayersDependOnNoFrameworkOrAdapter() throws IOException {
		try (Stream<Path> files = Files.walk(SOURCES)) {
			List<String> violations = files.filter(HexagonalArchitectureTests::isInnerLayer)
				.flatMap(HexagonalArchitectureTests::forbiddenImports)
				.toList();

			assertThat(violations).isEmpty();
		}
	}

	private static boolean isInnerLayer(Path file) {
		String path = file.toString().replace('\\', '/');
		return path.endsWith(".java") && (path.contains("/domain/") || path.contains("/application/"));
	}

	private static Stream<String> forbiddenImports(Path file) {
		try {
			return Files.readAllLines(file)
				.stream()
				.filter(line -> line.startsWith("import "))
				.filter(line -> FORBIDDEN_IMPORTS.stream().anyMatch(line::contains))
				.map(line -> file.getFileName() + ": " + line);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

}
