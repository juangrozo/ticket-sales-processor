package co.com.ticketing.scheduler;

import co.com.ticketing.usecase.expirereservations.ExpireReservationsUseCase;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs the expiration and reconciliation sweep in the {@code worker} role. A reactive interval is
 * used instead of {@code @Scheduled}: runs never overlap, no thread waits for I/O, and the
 * subscription is cancelled cleanly on shutdown.
 */
@Log4j2
@Component
@ConditionalOnExpression("'${app.role:all}' matches 'worker|all'")
public class ReservationSweeper implements ApplicationListener<ApplicationReadyEvent>, DisposableBean {

    private final ExpireReservationsUseCase expireReservationsUseCase;
    private final Duration interval;
    private final AtomicReference<Disposable> subscription = new AtomicReference<>();

    public ReservationSweeper(ExpireReservationsUseCase expireReservationsUseCase,
                              @Value("${app.sweeper.interval:30s}") Duration interval) {
        this.expireReservationsUseCase = expireReservationsUseCase;
        this.interval = interval;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        log.info("Reservation sweeper started, every {}", interval);
        subscription.set(expireReservationsUseCase.sweepEvery(interval)
                .filter(result -> result.expired() + result.requeuedPending() + result.requeuedConfirming() > 0)
                .subscribe(result -> log.info("Sweep: {}", result)));
    }

    @Override
    public void destroy() {
        Disposable current = subscription.getAndSet(null);
        if (current != null) {
            current.dispose();
        }
    }
}
