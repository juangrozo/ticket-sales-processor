#!/bin/sh
# Creates the DynamoDB tables used by the application. Idempotent: existing tables are skipped.
set -eu

ENDPOINT="${DYNAMODB_ENDPOINT:-http://localhost:8000}"

ddb() {
  aws dynamodb --endpoint-url "$ENDPOINT" "$@"
}

until ddb list-tables >/dev/null 2>&1; do
  echo "Waiting for DynamoDB at $ENDPOINT..."
  sleep 1
done

create_table() {
  name="$1"
  shift
  if ddb describe-table --table-name "$name" >/dev/null 2>&1; then
    echo "Table $name already exists"
  else
    ddb create-table --table-name "$name" --billing-mode PAY_PER_REQUEST "$@" >/dev/null
    echo "Table $name created"
  fi
}

create_table ticketing-events \
  --attribute-definitions AttributeName=eventId,AttributeType=S \
  --key-schema AttributeName=eventId,KeyType=HASH

# GSI used by the sweeper: expired reservations and stale PENDING orders, never a Scan.
create_table ticketing-orders \
  --attribute-definitions \
    AttributeName=orderId,AttributeType=S \
    AttributeName=orderStatus,AttributeType=S \
    AttributeName=statusChangedAt,AttributeType=N \
  --key-schema AttributeName=orderId,KeyType=HASH \
  --global-secondary-indexes \
    'IndexName=orderStatus-statusChangedAt-index,KeySchema=[{AttributeName=orderStatus,KeyType=HASH},{AttributeName=statusChangedAt,KeyType=RANGE}],Projection={ProjectionType=ALL}'

create_table ticketing-tickets \
  --attribute-definitions \
    AttributeName=orderId,AttributeType=S \
    AttributeName=ticketId,AttributeType=S \
  --key-schema AttributeName=orderId,KeyType=HASH AttributeName=ticketId,KeyType=RANGE

create_table ticketing-audit \
  --attribute-definitions \
    AttributeName=entityId,AttributeType=S \
    AttributeName=auditId,AttributeType=S \
  --key-schema AttributeName=entityId,KeyType=HASH AttributeName=auditId,KeyType=RANGE

create_table ticketing-idempotency \
  --attribute-definitions AttributeName=idempotencyKey,AttributeType=S \
  --key-schema AttributeName=idempotencyKey,KeyType=HASH

ddb update-time-to-live --table-name ticketing-idempotency \
  --time-to-live-specification Enabled=true,AttributeName=expiresAt >/dev/null 2>&1 || true

echo "DynamoDB tables ready"
