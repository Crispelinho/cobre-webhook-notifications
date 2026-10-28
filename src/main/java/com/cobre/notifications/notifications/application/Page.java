package com.cobre.notifications.notifications.application;

import java.util.List;

public record Page<T>(List<T> items, int page, int size, long totalElements) {

	public Page {
		items = List.copyOf(items);
	}

	public int totalPages() {
		return (int) ((totalElements + size - 1) / size);
	}

}
