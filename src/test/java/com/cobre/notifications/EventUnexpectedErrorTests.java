package com.cobre.notifications;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.cobre.notifications.events.application.port.in.IngestEvent;

/**
 * An {@link IllegalArgumentException} that is not an event validation failure must never leak its message.
 */
@SpringBootTest(properties = "app.outbox.relay.enabled=false")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class EventUnexpectedErrorTests {

	private static final TestJwtIssuer ISSUER = TestJwtIssuer.trusted();

	@Autowired
	private MockMvc mvc;

	@MockitoBean
	private IngestEvent ingestEvent;

	@Test
	void genericIllegalArgumentReturnsAFixedMessage() throws Exception {
		when(ingestEvent.ingest(any(), any(), any(), any())).thenThrow(new IllegalArgumentException("internal-secret-detail"));

		mvc.perform(post("/events")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + ISSUER.token("producer-service", "events:write"))
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"event_id\":\"E1\",\"client_id\":\"C1\",\"event_type\":\"credit_deposit\",\"content\":\"x\"}"))
			.andExpect(status().isInternalServerError())
			.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
			.andExpect(jsonPath("$.detail").value("Unexpected error."))
			.andExpect(content().string(not(containsString("internal-secret-detail"))));
	}

}
