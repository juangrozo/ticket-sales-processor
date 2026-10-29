output "kms_key_arn" {
  description = "Key that encrypts tables and queues."
  value       = aws_kms_key.data.arn
}

output "table_arns" {
  description = "ARN of each table, by logical name."
  value = {
    events      = aws_dynamodb_table.events.arn
    orders      = aws_dynamodb_table.orders.arn
    tickets     = aws_dynamodb_table.tickets.arn
    audit       = aws_dynamodb_table.audit.arn
    idempotency = aws_dynamodb_table.idempotency.arn
  }
}

output "table_names" {
  description = "Name of each table, by logical name."
  value = {
    events      = aws_dynamodb_table.events.name
    orders      = aws_dynamodb_table.orders.name
    tickets     = aws_dynamodb_table.tickets.name
    audit       = aws_dynamodb_table.audit.name
    idempotency = aws_dynamodb_table.idempotency.name
  }
}

output "queue_arns" {
  description = "ARN of each work queue."
  value       = { for name, queue in aws_sqs_queue.this : name => queue.arn }
}

output "queue_urls" {
  description = "URL of each work queue."
  value       = { for name, queue in aws_sqs_queue.this : name => queue.url }
}

output "queue_names" {
  description = "Name of each work queue, for metrics and alarms."
  value       = { for name, queue in aws_sqs_queue.this : name => queue.name }
}

output "dlq_names" {
  description = "Name of each dead-letter queue, for alarms."
  value       = { for name, queue in aws_sqs_queue.dlq : name => queue.name }
}
