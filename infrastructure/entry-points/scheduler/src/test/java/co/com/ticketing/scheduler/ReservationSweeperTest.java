package co.com.ticketing.scheduler;

import co.com.ticketing.usecase.expirereservations.ExpireReservationsUseCase;
import co.com.ticketing.usecase.expirereservations.ExpireReservationsUseCase.SweepResult;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.publisher.TestPublisher;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReservationSweeperTest {

    @Test
    void shouldSubscribeOnStartupAndCancelOnShutdown() {
        ExpireReservationsUseCase useCase = mock(ExpireReservationsUseCase.class);
        TestPublisher<SweepResult> sweeps = TestPublisher.create();
        when(useCase.sweepEvery(Duration.ofSeconds(5))).thenReturn(sweeps.flux());
        ReservationSweeper sweeper = new ReservationSweeper(useCase, Duration.ofSeconds(5));

        sweeper.onApplicationEvent(null);
        sweeps.next(new SweepResult(0, 0, 0), new SweepResult(2, 1, 0));
        sweeps.assertSubscribers(1);

        sweeper.destroy();
        sweeps.assertCancelled();
        sweeper.destroy();
        assertThat(sweeps.wasCancelled()).isTrue();
    }

    @Test
    void shutdownBeforeStartupShouldBeSafe() {
        ExpireReservationsUseCase useCase = mock(ExpireReservationsUseCase.class);
        when(useCase.sweepEvery(Duration.ofSeconds(5))).thenReturn(Flux.never());

        new ReservationSweeper(useCase, Duration.ofSeconds(5)).destroy();
    }
}
