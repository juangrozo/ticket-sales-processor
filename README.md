# Ticket Sales Processor

> **Borrador en construcción.** Las secciones marcadas como *(pendiente)* describen lo que falta por implementar.

Backend reactivo y orientado a eventos para la venta de entradas a eventos (conciertos, teatro, deportes) bajo picos de demanda. Resuelve los tres problemas del sistema actual:

| Problema | Cómo se resuelve |
|---|---|
| Sobreventa (asientos vendidos a varias personas) | Escrituras condicionales atómicas en DynamoDB: el inventario solo se descuenta si `available >= quantity`. |
| Compras duplicadas | `Idempotency-Key` en la API y transiciones de estado condicionales en el consumidor (un mensaje repetido no tiene efecto). |
| Timeouts bajo alta concurrencia | La API solo registra la orden, la encola y responde `202 Accepted`; el trabajo pesado lo hace un consumidor asíncrono de SQS. Todo el stack es no bloqueante (WebFlux + AWS SDK v2 asíncrono). |

## Stack

- Java 25 (records, `sealed`, pattern matching con guards y patrones sin nombre)
- Spring Boot 4.1 + Spring WebFlux (API funcional Router/Handler)
- DynamoDB (AWS SDK v2, `DynamoDbEnhancedAsyncClient`)
- SQS (AWS SDK v2, `SqsAsyncClient`) con DLQ
- Clean Architecture generada con [scaffold-clean-architecture](https://github.com/bancolombia/scaffold-clean-architecture) de Bancolombia 5.0.5
- Docker y Docker Compose
- Tests: JUnit 5, Mockito, reactor-test (`StepVerifier`), BlockHound, ArchUnit, JaCoCo y Pitest
- Terraform para la infraestructura en AWS *(pendiente)*

## Arquitectura

```mermaid
flowchart LR
    client([Cliente]) -->|HTTP| api

    subgraph app[Aplicación: una imagen, dos roles]
        api[api<br/>WebFlux]
        worker[worker<br/>listener SQS + scheduler]
    end

    api -->|PutItem orden PENDING| ddb[(DynamoDB)]
    api -->|SendMessage| q1[[order-placed]]
    q1 --> worker
    worker -->|TransactWriteItems<br/>orden + inventario + tickets + auditoría| ddb
    api -->|SendMessage| q2[[order-confirmation]]
    q2 --> worker
    q1 -. 5 intentos fallidos .-> dlq1[[order-placed-dlq]]
    q2 -. 5 intentos fallidos .-> dlq2[[order-confirmation-dlq]]
```

**Capas** (módulos Gradle del scaffold):

| Capa | Módulo | Contenido |
|---|---|---|
| Dominio | `domain/model` | Entidades (records), máquinas de estado, errores de negocio y puertos (gateways). Sin dependencias de frameworks. |
| Casos de uso | `domain/usecase` | Orquestación reactiva de los flujos de negocio. *(pendiente)* |
| Infraestructura | `infrastructure/driven-adapters` | DynamoDB y SQS (envío). |
| Infraestructura | `infrastructure/entry-points` | API WebFlux y listener de SQS. |
| Aplicación | `applications/app-service` | Ensamblado, configuración y arranque. |

`./gradlew validateStructure` y los tests de ArchUnit verifican que las dependencias entre capas apunten siempre hacia el dominio.

**Roles api y worker** *(pendiente)*: es una sola aplicación y una sola imagen, que arranca en el rol `api`, `worker` o `all` según la propiedad `app.role`. En producción permite escalar la API por peticiones y el worker por profundidad de cola, aislar la carga pesada de la latencia HTTP y dar a cada rol su propio IAM role de mínimo privilegio.

## Modelo de estados

El enunciado mezcla estados de entrada y de orden. Se modelan como dos máquinas de estado separadas, porque una orden existe (encolada) antes de que exista ninguna entrada.

### Entrada (`TicketStatus`)

```mermaid
stateDiagram-v2
    [*] --> AVAILABLE
    AVAILABLE --> RESERVED: el consumidor reserva
    AVAILABLE --> COMPLIMENTARY: cortesía (admin)
    RESERVED --> PENDING_CONFIRMATION: el cliente confirma
    RESERVED --> AVAILABLE: la reserva expira (10 min)
    PENDING_CONFIRMATION --> SOLD: pago aprobado
    PENDING_CONFIRMATION --> AVAILABLE: pago rechazado
    SOLD --> [*]
    COMPLIMENTARY --> [*]
```

- RESERVED y PENDING_CONFIRMATION ocupan inventario pero no son ventas.
- SOLD es final e irreversible. COMPLIMENTARY es final y no contable.
- Una reserva en PENDING_CONFIRMATION no expira: el pago está en curso.

### Orden (`OrderStatus`)

```mermaid
stateDiagram-v2
    [*] --> PENDING: POST /orders
    PENDING --> AWAITING_CONFIRMATION: entradas reservadas
    PENDING --> REJECTED: sin inventario
    AWAITING_CONFIRMATION --> CONFIRMING: POST /orders/{id}/confirm
    AWAITING_CONFIRMATION --> EXPIRED: 10 min sin confirmar
    CONFIRMING --> CONFIRMED: pago aprobado
    CONFIRMING --> REJECTED: pago rechazado
    CONFIRMED --> [*]
    REJECTED --> [*]
    EXPIRED --> [*]
```

`GET /orders/{id}` devuelve el estado de la orden y el de sus entradas.

## Flujo de compra

```mermaid
sequenceDiagram
    autonumber
    actor C as Cliente
    participant A as api
    participant D as DynamoDB
    participant Q as SQS
    participant W as worker

    C->>A: POST /orders (Idempotency-Key)
    A->>D: PutItem orden PENDING (condicional por idempotencia)
    A->>Q: SendMessage order-placed
    A-->>C: 202 Accepted + orderId
    Q->>W: ReceiveMessage
    W->>D: TransactWriteItems<br/>orden PENDING→AWAITING_CONFIRMATION<br/>inventario available -= n si available >= n<br/>n tickets RESERVED + auditoría
    alt sin inventario
        W->>D: orden → REJECTED
    end
    W->>Q: DeleteMessage (solo tras procesar)
    C->>A: POST /orders/{id}/confirm
    A->>D: orden → CONFIRMING si expiresAt > now<br/>tickets → PENDING_CONFIRMATION
    A->>Q: SendMessage order-confirmation
    A-->>C: 202 Accepted
    Q->>W: ReceiveMessage
    W->>D: pago simulado: tickets → SOLD, orden → CONFIRMED
```

Un proceso periódico (`@Scheduled`) libera las reservas vencidas y reencola las órdenes que se quedaron en PENDING *(pendiente)*.

## Cómo ejecutarlo

### Requisitos

- Docker con Docker Compose v2
- Java 25, solo para compilar o ejecutar los tests fuera de Docker

### Levantar todo con Docker

```bash
docker compose up -d --build
curl http://localhost:8080/actuator/health
```

| Servicio | Puerto | Descripción |
|---|---|---|
| `app` | 8080 | La aplicación |
| `dynamodb` | 8000 | DynamoDB Local, en memoria |
| `dynamodb-init` | — | Crea las tablas y termina (script idempotente) |
| `elasticmq` | 9324 | Emulador de SQS con las colas y DLQ ya configuradas |
| `elasticmq` | 9325 | Panel de estadísticas de las colas |

Para detenerlo: `docker compose down`.

### Ejecutar los tests

```bash
./gradlew test          # tests de todos los módulos
./gradlew build         # compila, ejecuta los tests y la mutación (Pitest)
./gradlew validateStructure
```

El informe de cobertura de cada módulo queda en `<módulo>/build/reports/jacocoHtml/index.html`.

### Configuración

La aplicación se configura por variables de entorno; los valores por defecto apuntan a los emuladores locales.

| Variable | Por defecto |
|---|---|
| `AWS_REGION` | `us-east-1` |
| `DYNAMODB_ENDPOINT` | `http://localhost:8000` |
| `SQS_ENDPOINT` | `http://localhost:9324` |
| `ORDER_QUEUE_URL` | `http://localhost:9324/000000000000/order-placed` |

En local las credenciales son valores ficticios, porque los emuladores no las validan. En AWS no hay credenciales estáticas: se usan IAM task roles.

## Endpoints *(pendiente)*

| Método | Ruta | Descripción |
|---|---|---|
| `POST` | `/events` | Crear un evento |
| `GET` | `/events` | Listar eventos |
| `GET` | `/events/{id}` | Consultar un evento |
| `GET` | `/events/{id}/availability` | Disponibilidad actual (también en tiempo real con SSE) |
| `GET` | `/events/{id}/report` | Conteo por estado; las cortesías no cuentan como venta |
| `POST` | `/events/{id}/complimentary` | Asignar cortesías (admin) |
| `POST` | `/orders` | Crear una orden (`Idempotency-Key` obligatorio); responde `202` |
| `GET` | `/orders/{id}` | Estado de la orden y de sus entradas |
| `POST` | `/orders/{id}/confirm` | Confirmar la compra (pago simulado); responde `202` |

Los ejemplos con `curl` y la colección de solicitudes se añaden cuando los endpoints estén implementados.

## Decisiones arquitectónicas

### Concurrencia sin sobreventa
- **Contador atómico con escritura condicional.** Reservar es `ADD available -n` con la condición `available >= n`, sin campo de versión. Con versión, dos reservas simultáneas fallarían aunque hubiera stock y en un evento popular se dispararían reintentos en cascada.
- **Optimistic locking (`version`) solo en las órdenes.** Los tickets se escriben condicionados a su estado esperado, que cumple el mismo papel.
- **`TransactWriteItems`** actualiza en una sola operación atómica la orden, el inventario, los tickets y la auditoría. Con un máximo de 10 entradas por orden se queda muy por debajo del límite de 100 elementos por transacción.
- **Carrera entre confirmar y expirar.** Confirmar exige `expiresAt > now`; expirar exige `expiresAt <= now`. Solo una de las dos puede ganar, y el dominio lo cubre con un test.

### Mensajería
- **Entrega al menos una vez.** El mensaje se borra solo después de procesarlo. Tras 5 intentos fallidos va a la DLQ.
- **Consumidor idempotente.** Toda transición es condicional al estado actual de la orden, así que un mensaje duplicado no tiene efecto y no hace falta una tabla de deduplicación.
- **Doble escritura (DynamoDB + SQS).** Si el envío a SQS falla después de guardar la orden, esta queda en PENDING y el proceso periódico la reencola. En producción se usaría el patrón outbox.

### Expiración de reservas
- Un barrido periódico consulta un índice secundario (`orderStatus` + `statusChangedAt`), nunca hace un `Scan`.
- No depende del TTL de DynamoDB, que puede tardar horas en borrar un elemento.

### Modelo de datos
Se usan varias tablas (`ticketing-events`, `-orders`, `-tickets`, `-audit` e `-idempotency`) en lugar de una sola: es más fácil de explicar y mantener, y `TransactWriteItems` funciona igual entre tablas. La auditoría es de solo inserción y se escribe en la misma transacción que el cambio de estado, así que ninguna transición queda sin registrar.

### Dominio con Java 25
- Las entidades son `record` inmutables; cada transición devuelve una instancia nueva.
- `OrderTransition` es una `sealed interface` y `Order.apply` la resuelve con un `switch` con guards (`when`) y sin `default`: añadir una transición nueva rompe la compilación hasta que se maneja.
- `DomainException` también es `sealed`, para mapear cada error de negocio a su respuesta HTTP con un `switch` exhaustivo.

### Sin virtual threads
WebFlux ya es no bloqueante: unos pocos hilos atienden miles de peticiones concurrentes. Los virtual threads resuelven el problema del código bloqueante, que aquí no existe. BlockHound, activo en los tests, falla si algún flujo bloquea.

### Emuladores locales en lugar de LocalStack
Desde la versión 2026.03.0, la imagen de LocalStack exige un token de autenticación. Para que `docker compose up` funcione en cualquier máquina sin cuentas ni secretos, se usan DynamoDB Local y ElasticMQ, compatible con la API de SQS (colas, DLQ, visibility timeout y long polling).

## Seguridad *(parcialmente pendiente)*
- Sin credenciales en el repositorio; la configuración entra por variables de entorno.
- El contenedor se ejecuta con un usuario sin privilegios.
- Idempotencia frente a reintentos maliciosos o del cliente: la misma `Idempotency-Key` con un cuerpo distinto responde `409`.
- Validación de entrada y máximo de 10 entradas por orden.
- Rate limiting en la API para limitar el abuso de recursos.

## Despliegue en AWS *(pendiente)*
Infraestructura en Terraform, validada con `terraform validate` y `tflint`, sin desplegar: ECS Fargate (servicios api y worker), DynamoDB on-demand con PITR, SQS con DLQ, KMS, IAM de mínimo privilegio, VPC endpoints y CloudWatch.

## Limitaciones y mejoras *(pendiente)*
- Partición caliente en eventos muy populares; se mitigaría con contadores fragmentados (sharded counters).
- Patrón outbox para eliminar la doble escritura.
- Autenticación con JWT.
- Trazas distribuidas (OpenTelemetry o X-Ray) a través de SQS.
