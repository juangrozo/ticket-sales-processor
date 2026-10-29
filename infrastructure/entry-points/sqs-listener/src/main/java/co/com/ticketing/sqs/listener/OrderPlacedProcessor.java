package co.com.ticketing.sqs.listener;

import co.com.ticketing.usecase.reservetickets.ReserveTicketsUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/** order-placed queue: reserves the tickets of the order. */
@Service
@RequiredArgsConstructor
public class OrderPlacedProcessor extends OrderMessageProcessor {

    private final ReserveTicketsUseCase reserveTicketsUseCase;

    @Override
    protected Mono<Void> handle(String orderId) {
        return reserveTicketsUseCase.reserve(orderId);
    }
}
