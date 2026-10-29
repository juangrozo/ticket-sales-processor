package co.com.ticketing.integration;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.reactive.function.client.WebClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.startupcheck.OneShotStartupCheckStrategy;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The whole application against real emulators: DynamoDB Local and ElasticMQ, with the same table
 * script and queue configuration that docker-compose uses. Many customers buy at once over HTTP;
 * exactly the available tickets must be reserved. Skipped when Docker is not available.
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ConcurrentPurchaseIntegrationTest {

    private static final int CAPACITY = 20;
    private static final int CUSTOMERS = 100;
    private static final String DEPLOYMENT = "../../deployment/local/";

    private static final Network NETWORK = Network.newNetwork();

    @Container
    private static final GenericContainer<?> DYNAMODB = new GenericContainer<>("amazon/dynamodb-local:3.3.2")
            .withNetwork(NETWORK)
            .withNetworkAliases("dynamodb")
            .withCommand("-jar", "DynamoDBLocal.jar", "-sharedDb", "-inMemory")
            .withExposedPorts(8000);

    @Container
    private static final GenericContainer<?> ELASTICMQ = new GenericContainer<>("softwaremill/elasticmq-native:1.7.1")
            .withCopyFileToContainer(MountableFile.forHostPath(DEPLOYMENT + "elasticmq.conf"), "/opt/elasticmq.conf")
            .withExposedPorts(9324)
            .waitingFor(Wait.forListeningPort());

    @LocalServerPort
    private int port;

    @BeforeAll
    static void createTables() {
        // The real init script, run exactly as docker-compose runs it
        try (GenericContainer<?> init = new GenericContainer<>("amazon/aws-cli:2.38.6")
                .withNetwork(NETWORK)
                .withCopyFileToContainer(MountableFile.forHostPath(DEPLOYMENT + "dynamodb-init.sh"), "/init/dynamodb-init.sh")
                .withEnv(Map.of("DYNAMODB_ENDPOINT", "http://dynamodb:8000", "AWS_ACCESS_KEY_ID", "local",
                        "AWS_SECRET_ACCESS_KEY", "local", "AWS_DEFAULT_REGION", "us-east-1"))
                .withCreateContainerCmdModifier(cmd -> cmd.withEntrypoint("sh", "/init/dynamodb-init.sh"))
                .withStartupCheckStrategy(new OneShotStartupCheckStrategy().withTimeout(Duration.ofMinutes(2)))) {
            init.start();
        }
    }

    @DynamicPropertySource
    static void emulators(DynamicPropertyRegistry registry) {
        System.setProperty("aws.accessKeyId", "local");
        System.setProperty("aws.secretAccessKey", "local");
        String dynamo = "http://" + DYNAMODB.getHost() + ":" + DYNAMODB.getMappedPort(8000);
        String sqs = "http://" + ELASTICMQ.getHost() + ":" + ELASTICMQ.getMappedPort(9324);
        registry.add("app.role", () -> "all");
        registry.add("aws.dynamodb.endpoint", () -> dynamo);
        registry.add("adapter.sqs.endpoint", () -> sqs);
        registry.add("adapter.sqs.queueUrl", () -> sqs + "/000000000000/order-placed");
        registry.add("adapter.sqs.confirmationQueueUrl", () -> sqs + "/000000000000/order-confirmation");
        registry.add("entrypoint.sqs.endpoint", () -> sqs);
        registry.add("entrypoint.sqs.queueUrl", () -> sqs + "/000000000000/order-placed");
        registry.add("entrypoint.sqs.confirmationQueueUrl", () -> sqs + "/000000000000/order-confirmation");
        registry.add("entrypoint.sqs.waitTimeSeconds", () -> "1");
        registry.add("app.sweeper.interval", () -> "1h");
        // This test measures concurrency, not rate limiting (covered by its own tests)
        registry.add("app.api.rate-limit-capacity", () -> "100000");
        registry.add("app.api.rate-limit-per-second", () -> "100000");
    }

    private WebClient client() {
        return WebClient.create("http://localhost:" + port);
    }

    private Map<String, Object> placeOrder(String eventId, int customer) {
        return client().post().uri("/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Customer-Id", "customer-" + customer)
                .header("Idempotency-Key", "key-" + customer)
                .bodyValue(Map.of("eventId", eventId, "quantity", 1))
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() { })
                .block();
    }

    private String status(String orderId, String customerId) {
        return (String) client().get().uri("/orders/{id}", orderId)
                .header("X-Customer-Id", customerId)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() { })
                .block()
                .get("orderStatus");
    }

    @Test
    void manyConcurrentBuyersShouldGetExactlyTheAvailableTickets() throws InterruptedException {
        String eventId = (String) client().post().uri("/events")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "Rock Fest", "venue", "Arena",
                        "startsAt", Instant.now().plus(Duration.ofDays(30)).toString(), "capacity", CAPACITY))
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() { })
                .block()
                .get("eventId");

        Map<Integer, String> orderByCustomer = Flux.range(0, CUSTOMERS)
                .flatMap(customer -> client().post().uri("/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Customer-Id", "customer-" + customer)
                        .header("Idempotency-Key", "key-" + customer)
                        .bodyValue(Map.of("eventId", eventId, "quantity", 1))
                        .retrieve()
                        .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() { })
                        .map(body -> Map.entry(customer, (String) body.get("orderId"))), CUSTOMERS)
                .collectMap(Map.Entry::getKey, Map.Entry::getValue)
                .block();
        List<String> orderIds = List.copyOf(orderByCustomer.values());
        assertThat(orderIds).hasSize(CUSTOMERS).doesNotHaveDuplicates();

        // The same request again must not create another order
        assertThat(placeOrder(eventId, 0).get("orderId")).isEqualTo(orderByCustomer.get(0));

        Map<String, Long> byStatus = Map.of();
        Instant deadline = Instant.now().plus(Duration.ofSeconds(60));
        while (Instant.now().isBefore(deadline)) {
            byStatus = orderByCustomer.entrySet().stream().collect(Collectors.groupingBy(
                    entry -> status(entry.getValue(), "customer-" + entry.getKey()), Collectors.counting()));
            if (!byStatus.containsKey("PENDING")) {
                break;
            }
            Thread.sleep(500);
        }

        assertThat(byStatus)
                .containsEntry("AWAITING_CONFIRMATION", (long) CAPACITY)
                .containsEntry("REJECTED", (long) (CUSTOMERS - CAPACITY))
                .doesNotContainKey("PENDING");
        Map<String, Object> availability = client().get().uri("/events/{id}/availability", eventId)
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() { })
                .block();
        assertThat(availability).containsEntry("available", 0).containsEntry("reserved", CAPACITY);
    }
}
