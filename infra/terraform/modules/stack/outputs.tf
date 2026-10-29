output "api_url" {
  description = "Public HTTPS endpoint of the API."
  value       = "https://${module.compute.alb_dns_name}"
}

output "ecr_repository_url" {
  description = "Where CI pushes the image."
  value       = module.compute.ecr_repository_url
}

output "admin_api_key_secret_arn" {
  description = "Set its value with: aws secretsmanager put-secret-value."
  value       = module.compute.admin_api_key_secret_arn
}

output "alarm_topic_arn" {
  description = "Topic that receives every alarm."
  value       = module.observability.alarm_topic_arn
}
