package com.cobre.notifications.subscriptions.adapters.in.rest;

import java.util.regex.Pattern;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.cobre.notifications.subscriptions.domain.InvalidSubscriptionException;
import com.cobre.notifications.subscriptions.domain.SubscriptionLimitReachedException;

/**
 * Maps request errors of {@link SubscriptionController} to RFC 9457 problems. Runs before Spring's generic problem
 * handler so unknown fields get a specific detail.
 */
@RestControllerAdvice(assignableTypes = SubscriptionController.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
class SubscriptionExceptionHandler {

	/**
	 * Field names are echoed only when they look like an identifier, so arbitrary input is never reflected.
	 */
	private static final Pattern PRINTABLE_FIELD = Pattern.compile("[A-Za-z0-9_]{1,64}");

	@ExceptionHandler(InvalidSubscriptionException.class)
	ProblemDetail invalidSubscription(InvalidSubscriptionException ex) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
	}

	@ExceptionHandler(SubscriptionLimitReachedException.class)
	ProblemDetail limitReached(SubscriptionLimitReachedException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
		problem.setProperty("code", SubscriptionLimitReachedException.CODE);
		return problem;
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	ProblemDetail unreadableBody(HttpMessageNotReadableException ex) {
		for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
			if (cause instanceof UnknownFieldException unknown) {
				String detail = PRINTABLE_FIELD.matcher(unknown.field()).matches()
						? "Field '" + unknown.field() + "' is not accepted by this operation."
						: "The request body contains a field that this operation does not accept.";
				return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
			}
		}
		return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
				"The request body is missing or is not valid JSON for this operation.");
	}

}
