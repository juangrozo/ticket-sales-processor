package co.com.ticketing.model.ticket.gateways;

import co.com.ticketing.model.ticket.Ticket;
import reactor.core.publisher.Flux;

public interface TicketRepository {

    Flux<Ticket> findByOrderId(String orderId);
}
