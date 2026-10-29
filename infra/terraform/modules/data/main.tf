# State and messaging. Same schema as deployment/local/dynamodb-init.sh and elasticmq.conf, so the
# local stack and AWS only differ in endpoints.

# One customer-managed key encrypts tables and queues: key policy and rotation are under our control
# and every use is recorded in CloudTrail.
resource "aws_kms_key" "data" {
  description             = "${var.name} DynamoDB and SQS encryption"
  enable_key_rotation     = true
  deletion_window_in_days = 30
}

resource "aws_kms_alias" "data" {
  name          = "alias/${var.name}-data"
  target_key_id = aws_kms_key.data.key_id
}

locals {
  # On-demand: traffic is spiky (ticket releases) and unpredictable, so paying per request beats
  # provisioning for the peak.
  billing_mode = "PAY_PER_REQUEST"
}

resource "aws_dynamodb_table" "events" {
  name                        = "${var.name}-events"
  billing_mode                = local.billing_mode
  hash_key                    = "eventId"
  deletion_protection_enabled = var.deletion_protection

  attribute {
    name = "eventId"
    type = "S"
  }

  point_in_time_recovery {
    enabled = true
  }

  server_side_encryption {
    enabled     = true
    kms_key_arn = aws_kms_key.data.arn
  }
}

resource "aws_dynamodb_table" "orders" {
  name                        = "${var.name}-orders"
  billing_mode                = local.billing_mode
  hash_key                    = "orderId"
  deletion_protection_enabled = var.deletion_protection

  attribute {
    name = "orderId"
    type = "S"
  }

  attribute {
    name = "orderStatus"
    type = "S"
  }

  attribute {
    name = "statusChangedAt"
    type = "N"
  }

  # Used by the sweeper to find expired reservations and stuck orders without a Scan
  global_secondary_index {
    name            = "orderStatus-statusChangedAt-index"
    projection_type = "ALL"

    key_schema {
      attribute_name = "orderStatus"
      key_type       = "HASH"
    }

    key_schema {
      attribute_name = "statusChangedAt"
      key_type       = "RANGE"
    }
  }

  point_in_time_recovery {
    enabled = true
  }

  server_side_encryption {
    enabled     = true
    kms_key_arn = aws_kms_key.data.arn
  }
}

resource "aws_dynamodb_table" "tickets" {
  name                        = "${var.name}-tickets"
  billing_mode                = local.billing_mode
  hash_key                    = "orderId"
  range_key                   = "ticketId"
  deletion_protection_enabled = var.deletion_protection

  attribute {
    name = "orderId"
    type = "S"
  }

  attribute {
    name = "ticketId"
    type = "S"
  }

  point_in_time_recovery {
    enabled = true
  }

  server_side_encryption {
    enabled     = true
    kms_key_arn = aws_kms_key.data.arn
  }
}

resource "aws_dynamodb_table" "audit" {
  name                        = "${var.name}-audit"
  billing_mode                = local.billing_mode
  hash_key                    = "entityId"
  range_key                   = "auditId"
  deletion_protection_enabled = var.deletion_protection

  attribute {
    name = "entityId"
    type = "S"
  }

  attribute {
    name = "auditId"
    type = "S"
  }

  point_in_time_recovery {
    enabled = true
  }

  server_side_encryption {
    enabled     = true
    kms_key_arn = aws_kms_key.data.arn
  }
}

resource "aws_dynamodb_table" "idempotency" {
  name                        = "${var.name}-idempotency"
  billing_mode                = local.billing_mode
  hash_key                    = "idempotencyKey"
  deletion_protection_enabled = var.deletion_protection

  attribute {
    name = "idempotencyKey"
    type = "S"
  }

  # Expired keys are deleted for free; the application ignores them even before TTL runs
  ttl {
    attribute_name = "expiresAt"
    enabled        = true
  }

  point_in_time_recovery {
    enabled = true
  }

  server_side_encryption {
    enabled     = true
    kms_key_arn = aws_kms_key.data.arn
  }
}

resource "aws_sqs_queue" "dlq" {
  for_each                          = toset(var.queue_names)
  name                              = "${var.name}-${each.key}-dlq"
  message_retention_seconds         = 1209600 # 14 days, the maximum, to investigate failures
  kms_master_key_id                 = aws_kms_key.data.arn
  kms_data_key_reuse_period_seconds = 300
}

resource "aws_sqs_queue" "this" {
  for_each                          = toset(var.queue_names)
  name                              = "${var.name}-${each.key}"
  visibility_timeout_seconds        = 30
  receive_wait_time_seconds         = 20 # Long polling: fewer empty receives, lower cost
  message_retention_seconds         = 345600
  kms_master_key_id                 = aws_kms_key.data.arn
  kms_data_key_reuse_period_seconds = 300

  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.dlq[each.key].arn
    maxReceiveCount     = 5
  })
}

resource "aws_sqs_queue_redrive_allow_policy" "dlq" {
  for_each  = toset(var.queue_names)
  queue_url = aws_sqs_queue.dlq[each.key].id

  redrive_allow_policy = jsonencode({
    redrivePermission = "byQueue"
    sourceQueueArns   = [aws_sqs_queue.this[each.key].arn]
  })
}
