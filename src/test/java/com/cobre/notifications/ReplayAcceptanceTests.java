package com.cobre.notifications;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.jayway.jsonpath.JsonPath;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * One test per scenario in {@code openspec/changes/add-notification-replay/specs/self-service-api/spec.md}, run against
 * the seeded sample data. The scenarios use different notification events because a replay changes its event.
 */
@SpringBootTest(properties = { "app.seed.enabled=true",
		// Replays change the seeded rows. A property no other test sets gives this class its own context and
		// containers, so SelfServiceApiAcceptanceTests keeps seeing the pristine sample data.
		"test.isolated-database=replay-acceptance" })
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ReplayAcceptanceTests {

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@Nested
	class ReplayAFailedNotificationEvent {

		@Test
		void replayStartsRoundTwoAsPendingAndKeepsRoundOneAttempts() throws Exception {
			jdbc.sql("INSERT INTO delivery_attempts (notification_id, round, attempt_number, attempted_at, "
					+ "http_status, error_type, latency_ms) VALUES ('EVT003', 1, 1, now(), 500, 'http_500', 20)")
				.update();

			jdbc.sql("UPDATE notifications SET last_error = 'http_500', attempts = 1 WHERE id = 'EVT003'").update();
			List<Map<String, Object>> attemptsBefore = attemptRows("EVT003");
			Object deliveredAtBefore = jdbc.sql("SELECT delivered_at FROM notifications WHERE id = 'EVT003'")
				.query()
				.singleRow()
				.get("delivered_at");

			mvc.perform(replayAs("CLIENT002", "EVT003"))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.notification_event_id").value("EVT003"))
				.andExpect(jsonPath("$.round").value(2))
				.andExpect(jsonPath("$.delivery_status").value("pending"));

			mvc.perform(readAs("CLIENT002", "EVT003"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.delivery_status").value("pending"))
				.andExpect(jsonPath("$.delivery_date").doesNotExist())
				.andExpect(jsonPath("$.attempts[0].round").value(1))
				.andExpect(jsonPath("$.attempts.length()").value(1));
			// The earlier rounds are history: replay never rewrites an attempt row or the attempts counter.
			assertThat(attemptRows("EVT003")).isEqualTo(attemptsBefore).hasSize(1);
			Map<String, Object> row = jdbc
				.sql("SELECT last_error, attempts, delivered_at FROM notifications WHERE id = 'EVT003'")
				.query()
				.singleRow();
			assertThat(row.get("last_error")).isNull();
			assertThat(row.get("attempts")).isEqualTo(1);
			assertThat(row.get("delivered_at")).isEqualTo(deliveredAtBefore);
			assertThat(jdbc.sql("SELECT round FROM outbox WHERE notification_id = 'EVT003' AND round = 2")
				.query(Integer.class)
				.list()).hasSize(1);
		}

	}

	@Nested
	class ReplayPreconditions {

		@Test
		void aNotificationEventThatIsNotFailedIsRejectedWithAProblem() throws Exception {
			mvc.perform(replayAs("CLIENT001", "EVT001"))
				.andExpect(status().isConflict())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.status").value(409));

			assertThat(jdbc.sql("SELECT delivery_status || ':' || round FROM notifications WHERE id = 'EVT001'")
				.query(String.class)
				.single()).isEqualTo("completed:1");
			assertThat(jdbc.sql("SELECT count(*) FROM outbox WHERE notification_id = 'EVT001'")
				.query(Long.class)
				.single()).isZero();
		}

		@Test
		void aReplayOfAFailedEventWhoseSubscriptionIsInactiveIsAcceptedAndStartsRoundTwo() throws Exception {
			String subscriptionId = jdbc.sql("SELECT subscription_id FROM notifications WHERE id = 'EVT009'")
				.query(String.class)
				.single();
			var before = jdbc.sql("SELECT delivery_status, delivery_date, last_error, version FROM notifications "
					+ "WHERE id = 'EVT009'")
				.query((rs, i) -> new Object[] { rs.getString(1), rs.getTimestamp(2), rs.getString(3), rs.getLong(4) })
				.single();
			jdbc.sql("UPDATE subscriptions SET active = false WHERE id = :id").param("id", subscriptionId).update();
			try {
				mvc.perform(replayAs("CLIENT003", "EVT009")).andExpect(status().isAccepted());
				assertThat(jdbc.sql("SELECT round FROM notifications WHERE id = 'EVT009'")
					.query(Integer.class)
					.single()).isEqualTo(2);
			}
			finally {
				// Put the seeded row back so the other scenarios of this class still see a failed round 1.
				jdbc.sql("UPDATE subscriptions SET active = true WHERE id = :id").param("id", subscriptionId).update();
				jdbc.sql("DELETE FROM outbox WHERE notification_id = 'EVT009' AND round = 2").update();
				jdbc.sql("UPDATE notifications SET round = 1, delivery_status = :s, delivery_date = :d, "
						+ "last_error = :e, version = :v WHERE id = 'EVT009'")
					.param("s", before[0])
					.param("d", before[1])
					.param("e", before[2])
					.param("v", before[3])
					.update();
			}
		}

		@Test
		void aNotificationEventOfAnotherClientIsNotFoundWithTheSameBodyAsAMissingOne() throws Exception {
			String other = mvc.perform(replayAs("CLIENT001", "EVT005"))
				.andExpect(status().isNotFound())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andReturn()
				.getResponse()
				.getContentAsString();
			String missing = mvc.perform(replayAs("CLIENT001", "DOES-NOT-EXIST"))
				.andExpect(status().isNotFound())
				.andReturn()
				.getResponse()
				.getContentAsString();

			// "instance" echoes the requested path; everything else must be identical.
			assertThat(withoutInstance(other)).isEqualTo(withoutInstance(missing)).containsKey("detail");
			assertThat(jdbc.sql("SELECT delivery_status FROM notifications WHERE id = 'EVT005'")
				.query(String.class)
				.single()).isEqualTo("failed");
		}

		@Test
		void aClientWithOnlyTheReadScopeIsForbidden() throws Exception {
			mvc.perform(post("/notification_events/EVT009/replay").header(HttpHeaders.AUTHORIZATION,
					"Bearer " + TestJwtIssuer.trusted().token("CLIENT003", "notifications:read")))
				.andExpect(status().isForbidden());

			assertThat(jdbc.sql("SELECT round FROM notifications WHERE id = 'EVT009'")
				.query(Integer.class)
				.single()).isEqualTo(1);
		}

		@Test
		void aRequestWithoutATokenIsUnauthorized() throws Exception {
			mvc.perform(post("/notification_events/EVT009/replay")).andExpect(status().isUnauthorized());
		}

		@Test
		void theReplayScopeDoesNotGrantReading() throws Exception {
			mvc.perform(get("/notification_events/EVT009").header(HttpHeaders.AUTHORIZATION,
					"Bearer " + TestJwtIssuer.trusted().token("CLIENT003", "notifications:replay")))
				.andExpect(status().isForbidden());
		}

	}

	@Nested
	class ConcurrentReplays {

		/**
		 * End to end over HTTP. Whichever request loses fails either the status check (it read the event after the
		 * winner committed) or the version guard (it read before); this test cannot tell which, so it only proves the
		 * outcome. The version guard on its own is proven in {@code JdbcReplayStoreAdapterTests}.
		 */
		@Test
		void ofTwoSimultaneousReplaysExactlyOneWins() throws Exception {
			int requests = 2;
			CountDownLatch ready = new CountDownLatch(requests);
			CountDownLatch go = new CountDownLatch(1);
			Callable<Integer> replay = () -> {
				ready.countDown();
				go.await();
				return mvc.perform(replayAs("CLIENT003", "EVT005")).andReturn().getResponse().getStatus();
			};
			List<Integer> statuses = new ArrayList<>();
			try (ExecutorService executor = Executors.newFixedThreadPool(requests)) {
				List<Future<Integer>> futures = new ArrayList<>();
				for (int i = 0; i < requests; i++) {
					futures.add(executor.submit(replay));
				}
				ready.await();
				go.countDown();
				for (Future<Integer> future : futures) {
					statuses.add(future.get());
				}
			}

			assertThat(statuses).containsExactlyInAnyOrder(202, 409);
			assertThat(jdbc.sql("SELECT round FROM notifications WHERE id = 'EVT005'")
				.query(Integer.class)
				.single()).isEqualTo(2);
			assertThat(jdbc.sql("SELECT count(*) FROM outbox WHERE notification_id = 'EVT005' AND round = 2")
				.query(Long.class)
				.single()).isEqualTo(1L);
		}

		@Test
		void aSecondReplayAfterTheFirstIsRejectedBecauseTheEventIsPendingAgain() throws Exception {
			mvc.perform(replayAs("CLIENT003", "EVT009")).andExpect(status().isAccepted());

			mvc.perform(replayAs("CLIENT003", "EVT009"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.detail").value("Only a failed notification event can be replayed."))
				// The response never echoes the id or the status.
				.andExpect(jsonPath("$.detail", not(containsString("EVT009"))));
		}

	}

	private List<Map<String, Object>> attemptRows(String notificationEventId) {
		return jdbc.sql("SELECT id, round, attempt_number, attempted_at, http_status, error_type, latency_ms "
				+ "FROM delivery_attempts WHERE notification_id = :id ORDER BY id")
			.param("id", notificationEventId)
			.query()
			.listOfRows();
	}

	private static Map<String, Object> withoutInstance(String json) {
		Map<String, Object> body = new HashMap<>(JsonPath.<Map<String, Object>>read(json, "$"));
		body.remove("instance");
		return body;
	}

	private static MockHttpServletRequestBuilder replayAs(String clientId, String notificationEventId) {
		return post("/notification_events/" + notificationEventId + "/replay").header(HttpHeaders.AUTHORIZATION,
				"Bearer " + TestJwtIssuer.trusted().token(clientId, "notifications:replay"));
	}

	private static MockHttpServletRequestBuilder readAs(String clientId, String notificationEventId) {
		return get("/notification_events/" + notificationEventId).header(HttpHeaders.AUTHORIZATION,
				"Bearer " + TestJwtIssuer.trusted().token(clientId, "notifications:read"));
	}

}
