package org.jevintegration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Minimal client for TypeSafe System One ({@code POST /v1/systemone}). Uses the JDK HttpClient and the
 * platform's Jackson, so it adds no dependency. Any failure returns empty: callers treat that as
 * "no decision" and fall back to their safe state, never as an error for the shopper.
 */
public class JevClient
{
	private static final Logger LOG = LoggerFactory.getLogger(JevClient.class);
	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final long FIRST_BACKOFF_MS = 500;
	private static final long MAX_BACKOFF_MS = 8000;

	private final HttpClient http;
	private final URI endpoint;
	private final String apiKey;
	private final String model;
	private final Duration timeout;
	private final int maxAttempts;

	public JevClient(final String endpoint, final String apiKey, final String model, final int timeoutMs,
			final int maxAttempts)
	{
		this.endpoint = URI.create(endpoint);
		this.apiKey = apiKey;
		this.model = model;
		this.timeout = Duration.ofMillis(timeoutMs);
		this.maxAttempts = maxAttempts;
		this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
	}

	/**
	 * @param state     only the fields the questions need; no customer ids or emails
	 * @param questions question id to question object ({@code type}, {@code instructions}, {@code criteria})
	 */
	public Optional<JevAnswers> ask(final Object state, final Map<String, ?> questions)
	{
		if (StringUtils.isBlank(apiKey))
		{
			LOG.warn("jev.api.key is not set, skipping Jev call");
			return Optional.empty();
		}
		final HttpRequest request = HttpRequest.newBuilder(endpoint)
				.timeout(timeout)
				.header("Authorization", "Bearer " + apiKey)
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(toJson(Map.of("model", model, "state", state, "questions", questions))))
				.build();

		long backoffMs = FIRST_BACKOFF_MS;
		for (int attempt = 1; attempt <= maxAttempts; attempt++)
		{
			try
			{
				final HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
				final int status = response.statusCode();
				if (status == 200)
				{
					final JsonNode root = MAPPER.readTree(response.body());
					return Optional.of(new JevAnswers(root.path("model").asText(), root.path("answers"),
							root.path("usage").path("input_tokens").asInt()));
				}
				if (status != 408 && status != 429 && status < 500) // same retryable set as TypeSafe's SDKs
				{
					// 401 bad key, 422 malformed question: retrying cannot help
					LOG.error("Jev rejected the request [status={}, body={}]", status, response.body());
					return Optional.empty();
				}
				backoffMs = Math.max(retryAfterMs(response).orElse(backoffMs), FIRST_BACKOFF_MS);
				LOG.warn("Jev busy [status={}, attempt={}/{}]", status, attempt, maxAttempts);
			}
			catch (final IOException e)
			{
				LOG.warn("Jev call failed [attempt={}/{}]: {}", attempt, maxAttempts, e.toString());
			}
			catch (final InterruptedException e)
			{
				Thread.currentThread().interrupt();
				return Optional.empty();
			}
			if (attempt < maxAttempts && !sleep(Math.min(backoffMs, MAX_BACKOFF_MS)))
			{
				return Optional.empty();
			}
			backoffMs *= 2;
		}
		return Optional.empty();
	}

	private static Optional<Long> retryAfterMs(final HttpResponse<?> response)
	{
		try
		{
			return response.headers().firstValue("retry-after").map(v -> Long.parseLong(v.trim()) * 1000);
		}
		catch (final NumberFormatException e)
		{
			return Optional.empty(); // ponytail: HTTP-date form ignored, exponential backoff covers it
		}
	}

	private static boolean sleep(final long ms)
	{
		try
		{
			Thread.sleep(ms);
			return true;
		}
		catch (final InterruptedException e)
		{
			Thread.currentThread().interrupt();
			return false;
		}
	}

	private static String toJson(final Object body)
	{
		try
		{
			return MAPPER.writeValueAsString(body);
		}
		catch (final JsonProcessingException e)
		{
			throw new IllegalArgumentException("State or questions are not serialisable to JSON", e);
		}
	}

	/** Answers keyed by question id. Accessors throw if the answer is missing, so a bad reply never passes as a 0. */
	public record JevAnswers(String model, JsonNode answers, int inputTokens)
	{
		public double noul(final String id)
		{
			return field(id, "noul").asDouble();
		}

		public String choice(final String id)
		{
			return field(id, "choice").asText();
		}

		public double score(final String id)
		{
			return field(id, "score").asDouble();
		}

		/** Choice and Score only; a Noul has no confidence. */
		public double confidence(final String id)
		{
			return field(id, "confidence").asDouble();
		}

		/** Choice and Score only: the probability of every option, keyed like the question's criteria. */
		public Map<String, Double> probabilities(final String id)
		{
			final JsonNode node = answers.path(id).path("probabilities");
			if (!node.isObject())
			{
				throw new IllegalStateException("Jev answer has no " + id + ".probabilities");
			}
			final Map<String, Double> probabilities = new LinkedHashMap<>();
			node.properties().forEach(entry -> probabilities.put(entry.getKey(), entry.getValue().asDouble()));
			return probabilities;
		}

		private JsonNode field(final String id, final String name)
		{
			final JsonNode value = answers.path(id).path(name);
			if (!value.isNumber() && !value.isTextual())
			{
				throw new IllegalStateException("Jev answer has no " + id + "." + name);
			}
			return value;
		}
	}
}
