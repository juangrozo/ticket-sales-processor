package co.com.ticketing.usecase.getorder;

import co.com.ticketing.model.exception.ResourceNotFoundException;
import co.com.ticketing.model.order.OrderDetails;
import co.com.ticketing.model.order.gateways.OrderRepository;
import co.com.ticketing.model.ticket.gateways.TicketRepository;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

@RequiredArgsConstructor
public class GetOrderUseCase {

    private final OrderRepository orderRepository;
    private final TicketRepository ticketRepository;

    public Mono<OrderDetails> get(String orderId) {
        return orderRepository.findById(orderId)
                .switchIfEmpty(Mono.error(() -> new ResourceNotFoundException("Order", orderId)))
                .flatMap(order -> ticketRepository.findByOrderId(orderId)
                        .collectList()
                        .map(tickets -> new OrderDetails(order, tickets)));
    }
}
