package com.cobre.notifications.notifications.adapters.in.rest;

import java.time.Instant;
import java.time.format.DateTimeParseException;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.cobre.notifications.notifications.application.PageQuery;
import com.cobre.notifications.notifications.application.port.in.GetNotificationEvent;
import com.cobre.notifications.notifications.application.port.in.ListNotificationEvents;
import com.cobre.notifications.notifications.application.port.in.ReplayNotificationEvent;
import com.cobre.notifications.notifications.domain.ClientId;
import com.cobre.notifications.notifications.domain.DeliveryStatus;
import com.cobre.notifications.notifications.domain.NotificationEventId;
import com.cobre.notifications.notifications.domain.NotificationFilter;
import com.cobre.notifications.shared.security.SecurityConfig;

/**
 * Self-service API. The client is always the one in the access token (OWASP API1); any client identity in the request
 * is ignored.
 */
@RestController
@RequestMapping("/notification_events")
class NotificationEventController {

	private final ListNotificationEvents listNotificationEvents;

	private final GetNotificationEvent getNotificationEvent;

	private final ReplayNotificationEvent replayNotificationEvent;

	NotificationEventController(ListNotificationEvents listNotificationEvents,
			GetNotificationEvent getNotificationEvent, ReplayNotificationEvent replayNotificationEvent) {
		this.listNotificationEvents = listNotificationEvents;
		this.getNotificationEvent = getNotificationEvent;
		this.replayNotificationEvent = replayNotificationEvent;
	}

	@GetMapping
	NotificationEventPageResponse list(@AuthenticationPrincipal Jwt jwt,
			@RequestParam(name = "created_from", required = false) String createdFrom,
			@RequestParam(name = "created_to", required = false) String createdTo,
			@RequestParam(name = "delivery_status", required = false) String deliveryStatus,
			@RequestParam(name = "page", defaultValue = "0") int page,
			@RequestParam(name = "size", defaultValue = "" + PageQuery.DEFAULT_SIZE) int size) {
		NotificationFilter filter;
		PageQuery pageQuery;
		try {
			filter = new NotificationFilter(instant(createdFrom), instant(createdTo),
					deliveryStatus == null ? null : DeliveryStatus.fromWireValue(deliveryStatus));
			pageQuery = new PageQuery(page, size);
		}
		catch (IllegalArgumentException | DateTimeParseException ex) {
			throw new InvalidRequestException(ex.getMessage());
		}
		return NotificationEventPageResponse.from(listNotificationEvents.list(clientId(jwt), filter, pageQuery));
	}

	@GetMapping("/{notification_event_id}")
	ResponseEntity<?> get(@AuthenticationPrincipal Jwt jwt,
			@PathVariable("notification_event_id") String notificationEventId) {
		return getNotificationEvent.get(clientId(jwt), new NotificationEventId(notificationEventId))
			.<ResponseEntity<?>>map(event -> ResponseEntity.ok(NotificationEventDetailResponse.from(event)))
			// Same answer whether the notification event is missing or owned by another client.
			.orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
				.body(ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Notification event not found.")));
	}

	@PostMapping("/{notification_event_id}/replay")
	ResponseEntity<?> replay(@AuthenticationPrincipal Jwt jwt,
			@PathVariable("notification_event_id") String notificationEventId) {
		return replayNotificationEvent.replay(clientId(jwt), new NotificationEventId(notificationEventId))
			.<ResponseEntity<?>>map(event -> ResponseEntity.accepted().body(ReplayResponse.from(event)))
			.orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
				.body(ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Notification event not found.")));
	}

	private static ClientId clientId(Jwt jwt) {
		return new ClientId(jwt.getClaimAsString(SecurityConfig.CLIENT_ID_CLAIM));
	}

	private static Instant instant(String value) {
		return value == null ? null : Instant.parse(value);
	}

}
