package com.cobre.notifications.events.adapters.in.rest;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.cobre.notifications.events.application.DuplicateEventException;

/**
 * Fixed texts only: nothing from the request body is reflected into error responses.
 */
@RestControllerAdvice(assignableTypes = EventController.class)
class EventExceptionHandler {

	@ExceptionHandler(DuplicateEventException.class)
	ProblemDetail duplicate() {
		return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, "An event with this event_id already exists.");
	}

	@ExceptionHandler(IllegalArgumentException.class)
	ProblemDetail invalid(IllegalArgumentException ex) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	ProblemDetail unreadable() {
		return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Request body is not valid JSON.");
	}

}
