package com.agp.bets.goforbroke;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableAsync
@EnableScheduling
public class GoforbrokeApplication {

	public static void main(String[] args) {
		loadDotEnvIfPresent();
		SpringApplication.run(GoforbrokeApplication.class, args);
	}

	// agp-bets/.env holds local secrets (e.g. ODDS_API_IO_KEY) but nothing wires it into Spring's
	// environment automatically - this reads it into System properties (only for keys not already
	// set as a real env var or system property, so those still win) before context startup, so
	// ${VAR} placeholders in application.yaml resolve regardless of where the process is launched
	// from (goforbroke/, backend/, agp-bets/, or the repo root).
	private static void loadDotEnvIfPresent() {
		List<String> candidatePaths = List.of(".env", "../.env", "../../.env", "../../../.env");
		for (String candidate : candidatePaths) {
			Path path = Path.of(candidate);
			if (!Files.isRegularFile(path)) {
				continue;
			}

			try {
				for (String line : Files.readAllLines(path)) {
					String trimmed = line.trim();
					if (trimmed.isEmpty() || trimmed.startsWith("#")) {
						continue;
					}

					int separator = trimmed.indexOf('=');
					if (separator <= 0) {
						continue;
					}

					String key = trimmed.substring(0, separator).trim();
					String value = trimmed.substring(separator + 1).trim();
					if (System.getenv(key) == null && System.getProperty(key) == null) {
						System.setProperty(key, value);
					}
				}
			} catch (IOException exception) {
				// Non-fatal: secrets can still arrive via real env vars.
			}
			return;
		}
	}

}
