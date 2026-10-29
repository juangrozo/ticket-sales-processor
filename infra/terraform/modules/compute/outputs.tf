output "alb_dns_name" {
  description = "Public DNS name of the API load balancer."
  value       = aws_lb.api.dns_name
}

output "alb_arn_suffix" {
  description = "Load balancer identifier for CloudWatch metrics."
  value       = aws_lb.api.arn_suffix
}

output "target_group_arn_suffix" {
  description = "Target group identifier for CloudWatch metrics."
  value       = aws_lb_target_group.api.arn_suffix
}

output "ecr_repository_url" {
  description = "Where CI pushes the image."
  value       = aws_ecr_repository.app.repository_url
}

output "cluster_name" {
  description = "ECS cluster name."
  value       = aws_ecs_cluster.this.name
}

output "service_names" {
  description = "ECS service names by role."
  value       = { api = aws_ecs_service.api.name, worker = aws_ecs_service.worker.name }
}

output "admin_api_key_secret_arn" {
  description = "Secret whose value must be set out of band."
  value       = aws_secretsmanager_secret.admin_api_key.arn
}
