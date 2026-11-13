package com.cobre.notifications.notifications.adapters.in.kafka;

import org.springframework.stereotype.Component;

import com.cobre.notifications.notifications.application.DispatchMessage;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads {@code {"notification_event_id": "...", "round": 1}}. Nothing else is trusted from the message.
 */
@Component
class DispatchPayloadParser {

	private final JsonMapper json;

	DispatchPayloadParser(JsonMapper json) {
		this.json = json;
	}

	DispatchMessage parse(String payload) {
		try {
			JsonNode root = json.readTree(payload);
			JsonNode id = root.get("notification_event_id");
			JsonNode round = root.get("round");
			if (id == null || !id.isString() || round == null || !round.isInt()) {
				throw new IllegalArgumentException("missing or mistyped field");
			}
			return new DispatchMessage(id.asString(), round.asInt());
		}
		catch (JacksonException | IllegalArgumentException | NullPointerException ex) {
			throw new MalformedDispatchMessageException("Unreadable dispatch message", ex);
		}
	}

}
