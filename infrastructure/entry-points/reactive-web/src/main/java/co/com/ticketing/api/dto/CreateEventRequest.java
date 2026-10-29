package co.com.ticketing.api.dto;

import java.time.Instant;

public record CreateEventRequest(String name, String venue, Instant startsAt, Integer capacity) {
}
