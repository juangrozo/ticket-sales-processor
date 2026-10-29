package co.com.ticketing.dynamodb;

import reactor.util.retry.Retry;
import reactor.util.retry.RetryBackoffSpec;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;

import java.time.Duration;
import java.util.List;
import java.util.Set;

/** Shared handling of DynamoDB transaction outcomes. */
final class TransactionSupport {

    static final String CONDITIONAL_CHECK_FAILED = "ConditionalCheckFailed";
    private static final Set<String> TRANSIENT_REASONS = Set.of("TransactionConflict", "ThrottlingError");

    private TransactionSupport() {
    }

    /**
     * Retries only transient cancellations, such as two transactions touching the same event item
     * at once. The SDK does not retry those by itself. Business condition failures are never retried.
     */
    static RetryBackoffSpec transientRetry() {
        return Retry.backoff(3, Duration.ofMillis(20))
                .maxBackoff(Duration.ofMillis(500))
                .jitter(0.5)
                .filter(TransactionSupport::isTransient)
                .onRetryExhaustedThrow((_, signal) -> signal.failure());
    }

    static boolean isTransient(Throwable error) {
        return error instanceof TransactionCanceledException canceled
                && reasons(canceled).stream().anyMatch(reason -> TRANSIENT_REASONS.contains(reason.code()))
                && !failedCondition(canceled);
    }

    static boolean failedConditionAt(TransactionCanceledException canceled, int index) {
        List<CancellationReason> reasons = reasons(canceled);
        return index < reasons.size() && CONDITIONAL_CHECK_FAILED.equals(reasons.get(index).code());
    }

    private static boolean failedCondition(TransactionCanceledException canceled) {
        return reasons(canceled).stream().anyMatch(reason -> CONDITIONAL_CHECK_FAILED.equals(reason.code()));
    }

    private static List<CancellationReason> reasons(TransactionCanceledException canceled) {
        return canceled.hasCancellationReasons() ? canceled.cancellationReasons() : List.of();
    }
}
