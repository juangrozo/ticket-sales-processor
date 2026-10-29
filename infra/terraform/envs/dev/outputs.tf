output "api_url" {
  description = "Public HTTPS endpoint of the API."
  value       = module.stack.api_url
}

output "ecr_repository_url" {
  description = "Where CI pushes the image."
  value       = module.stack.ecr_repository_url
}

output "admin_api_key_secret_arn" {
  description = "Set its value out of band."
  value       = module.stack.admin_api_key_secret_arn
}
