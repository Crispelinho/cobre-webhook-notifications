package com.cobre.notifications.notifications.adapters.in.rest;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

import com.cobre.notifications.notifications.application.Page;
import com.cobre.notifications.notifications.domain.NotificationEvent;

record NotificationEventPageResponse(List<NotificationEventResponse> data, int page, int size,
		@JsonProperty("total_elements") long totalElements, @JsonProperty("total_pages") int totalPages) {

	static NotificationEventPageResponse from(Page<NotificationEvent> page) {
		return new NotificationEventPageResponse(page.items().stream().map(NotificationEventResponse::from).toList(),
				page.page(), page.size(), page.totalElements(), page.totalPages());
	}

}
