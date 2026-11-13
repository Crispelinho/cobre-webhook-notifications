package com.cobre.notifications;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/** The OpenAPI document is public; the API itself stays protected. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class ApiDocumentationAcceptanceTests {

	@Autowired
	private MockMvc mvc;

	@Test
	void openApiDocumentIsPublic() throws Exception {
		mvc.perform(get("/v3/api-docs"))
			.andExpect(status().isOk())
			.andExpect(content().string(containsString("\"openapi\"")));
	}

	@Test
	void apiEndpointsStillRequireAToken() throws Exception {
		mvc.perform(get("/notification_events")).andExpect(status().isUnauthorized());
	}

}
