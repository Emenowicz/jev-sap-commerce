package org.jevintegration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import de.hybris.bootstrap.annotations.UnitTest;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;

import org.jevintegration.JevClient.JevAnswers;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.sun.net.httpserver.HttpServer;


/** Talks to a local fake of the Jev endpoint, so retries and parsing are tested over real HTTP. */
@UnitTest
public class JevClientTest
{
	private static final String OK = "{\"model\":\"jev-1.13.0\",\"answers\":{\"spam\":{\"type\":\"noul\",\"noul\":0.95}},"
			+ "\"usage\":{\"input_tokens\":296,\"output_tokens\":20}}";

	private final Deque<Integer> statuses = new ArrayDeque<>();
	private int calls;
	private HttpServer server;
	private JevClient client;

	@Before
	public void setUp() throws IOException
	{
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/v1/systemone", exchange -> {
			calls++;
			final int status = statuses.isEmpty() ? 200 : statuses.poll();
			final byte[] body = (status == 200 ? OK : "{\"detail\":\"x\"}").getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("retry-after", "0");
			exchange.sendResponseHeaders(status, body.length);
			exchange.getResponseBody().write(body);
			exchange.close();
		});
		server.start();
		client = new JevClient("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/systemone", "key", "jev-1.13.0",
				1000, 3);
	}

	@After
	public void tearDown()
	{
		server.stop(0);
	}

	@Test
	public void retriesOverloadThenParses()
	{
		statuses.add(529);
		statuses.add(429);
		final JevAnswers answers = client.ask("text", Map.of()).orElseThrow();
		assertEquals(3, calls);
		assertEquals("jev-1.13.0", answers.model());
		assertEquals(0.95, answers.noul("spam"), 1e-9);
		assertEquals(296, answers.inputTokens());
	}

	@Test
	public void doesNotRetryValidationError()
	{
		statuses.add(422);
		assertTrue(client.ask("text", Map.of()).isEmpty());
		assertEquals(1, calls);
	}

	@Test
	public void givesUpAfterMaxAttempts()
	{
		statuses.add(503);
		statuses.add(408);
		statuses.add(529);
		assertTrue(client.ask("text", Map.of()).isEmpty());
		assertEquals(3, calls);
	}

	@Test
	public void skipsCallsWithoutKey()
	{
		final JevClient noKey = new JevClient("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/systemone", "",
				"jev-1.13.0", 1000, 3);
		assertTrue(noKey.ask("text", Map.of()).isEmpty());
		assertEquals(0, calls);
	}

	@Test(expected = IllegalStateException.class)
	public void missingAnswerIsNotZero()
	{
		client.ask("text", Map.of()).orElseThrow().noul("abusive");
	}
}
