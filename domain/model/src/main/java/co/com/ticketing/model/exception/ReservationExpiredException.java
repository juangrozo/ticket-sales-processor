package co.com.ticketing.model.exception;

public final class ReservationExpiredException extends DomainException {

    public ReservationExpiredException(String orderId) {
        super("Reservation for order %s has expired".formatted(orderId));
    }
}
