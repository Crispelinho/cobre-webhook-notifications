package com.cobre.notifications.subscriptions.adapters.in.rest;

import java.net.URI;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.cobre.notifications.shared.security.SecurityConfig;
import com.cobre.notifications.subscriptions.application.CreatedSubscription;
import com.cobre.notifications.subscriptions.application.SubscriptionChanges;
import com.cobre.notifications.subscriptions.application.port.in.CreateSubscription;
import com.cobre.notifications.subscriptions.application.port.in.DeactivateSubscription;
import com.cobre.notifications.subscriptions.application.port.in.GetSubscription;
import com.cobre.notifications.subscriptions.application.port.in.ListSubscriptions;
import com.cobre.notifications.subscriptions.application.port.in.UpdateSubscription;
import com.cobre.notifications.subscriptions.domain.ClientId;
import com.cobre.notifications.subscriptions.domain.EventTypes;
import com.cobre.notifications.subscriptions.domain.SubscriptionId;
import com.cobre.notifications.subscriptions.domain.WebhookUrl;

/**
 * Subscription management for clients. The owner is always the client in the access token (OWASP API1); another
 * client's subscription is answered exactly like a missing one.
 */
@RestController
@RequestMapping("/subscriptions")
class SubscriptionController {

	private final CreateSubscription createSubscription;

	private final ListSubscriptions listSubscriptions;

	private final GetSubscription getSubscription;

	private final UpdateSubscription updateSubscription;

	private final DeactivateSubscription deactivateSubscription;

	SubscriptionController(CreateSubscription createSubscription, ListSubscriptions listSubscriptions,
			GetSubscription getSubscription, UpdateSubscription updateSubscription,
			DeactivateSubscription deactivateSubscription) {
		this.createSubscription = createSubscription;
		this.listSubscriptions = listSubscriptions;
		this.getSubscription = getSubscription;
		this.updateSubscription = updateSubscription;
		this.deactivateSubscription = deactivateSubscription;
	}

	@PostMapping
	ResponseEntity<CreatedSubscriptionResponse> create(@AuthenticationPrincipal Jwt jwt,
			@RequestBody CreateSubscriptionRequest request) {
		CreatedSubscription created = createSubscription.create(clientId(jwt), new WebhookUrl(request.url()),
				new EventTypes(request.eventTypes()));
		return ResponseEntity.created(URI.create("/subscriptions/" + created.subscription().id().value()))
			// The body carries the signing secret: no cache may keep it.
			.cacheControl(CacheControl.noStore())
			.body(CreatedSubscriptionResponse.from(created));
	}

	@GetMapping
	SubscriptionListResponse list(@AuthenticationPrincipal Jwt jwt) {
		return SubscriptionListResponse.from(listSubscriptions.list(clientId(jwt)));
	}

	@GetMapping("/{subscription_id}")
	ResponseEntity<?> get(@AuthenticationPrincipal Jwt jwt, @PathVariable("subscription_id") String subscriptionId) {
		return getSubscription.get(clientId(jwt), new SubscriptionId(subscriptionId))
			.<ResponseEntity<?>>map(subscription -> ResponseEntity.ok(SubscriptionResponse.from(subscription)))
			.orElseGet(SubscriptionController::notFound);
	}

	@PatchMapping("/{subscription_id}")
	ResponseEntity<?> update(@AuthenticationPrincipal Jwt jwt, @PathVariable("subscription_id") String subscriptionId,
			@RequestBody UpdateSubscriptionRequest request) {
		SubscriptionChanges changes = new SubscriptionChanges(request.active(),
				request.eventTypes() == null ? null : new EventTypes(request.eventTypes()));
		return updateSubscription.update(clientId(jwt), new SubscriptionId(subscriptionId), changes)
			.<ResponseEntity<?>>map(subscription -> ResponseEntity.ok(SubscriptionResponse.from(subscription)))
			.orElseGet(SubscriptionController::notFound);
	}

	@DeleteMapping("/{subscription_id}")
	ResponseEntity<?> deactivate(@AuthenticationPrincipal Jwt jwt,
			@PathVariable("subscription_id") String subscriptionId) {
		return deactivateSubscription.deactivate(clientId(jwt), new SubscriptionId(subscriptionId))
				? ResponseEntity.noContent().build() : notFound();
	}

	private static ClientId clientId(Jwt jwt) {
		return new ClientId(jwt.getClaimAsString(SecurityConfig.CLIENT_ID_CLAIM));
	}

	// Same answer whether the subscription is missing or owned by another client.
	private static ResponseEntity<?> notFound() {
		return ResponseEntity.status(HttpStatus.NOT_FOUND)
			.body(ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Subscription not found."));
	}

}
