package com.cobre.notifications.events.adapters.out.persistence;

import org.springframework.stereotype.Component;

import com.cobre.notifications.events.application.port.out.NotificationIdGenerator;
import com.cobre.notifications.shared.ids.UuidV7;

@Component
class UuidV7NotificationIdGenerator implements NotificationIdGenerator {

	@Override
	public String next() {
		return UuidV7.generate().toString();
	}

}
