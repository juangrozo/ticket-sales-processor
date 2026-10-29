output "vpc_id" {
  description = "VPC id."
  value       = aws_vpc.this.id
}

output "public_subnet_ids" {
  description = "Subnets for the load balancer."
  value       = aws_subnet.public[*].id
}

output "private_subnet_ids" {
  description = "Subnets for the tasks."
  value       = aws_subnet.private[*].id
}

output "dynamodb_endpoint_id" {
  description = "Gateway endpoint for DynamoDB, used to restrict table access to this VPC."
  value       = aws_vpc_endpoint.gateway["dynamodb"].id
}
