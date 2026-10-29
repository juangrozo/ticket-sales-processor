variable "name" {
  description = "Prefix for every resource name, for example ticketing-dev."
  type        = string
}

variable "vpc_id" {
  description = "VPC where everything runs."
  type        = string
}

variable "vpc_cidr" {
  description = "CIDR of the VPC, to scope security group rules."
  type        = string
}

variable "public_subnet_ids" {
  description = "Subnets for the load balancer."
  type        = list(string)
}

variable "private_subnet_ids" {
  description = "Subnets for the tasks."
  type        = list(string)
}

variable "certificate_arn" {
  description = "ACM certificate for the HTTPS listener."
  type        = string
}

variable "image_tag" {
  description = "Immutable image tag to deploy, usually the commit SHA."
  type        = string
}

variable "spring_profile" {
  description = "Spring profile: dev, cer or pdn."
  type        = string

  validation {
    condition     = contains(["dev", "cer", "pdn"], var.spring_profile)
    error_message = "The profile must be dev, cer or pdn."
  }
}

variable "table_arns" {
  description = "Table ARNs by logical name (events, orders, tickets, audit, idempotency)."
  type        = map(string)
}

variable "queue_arns" {
  description = "Work queue ARNs by name."
  type        = map(string)
}

variable "queue_urls" {
  description = "Work queue URLs by name."
  type        = map(string)
}

variable "queue_names" {
  description = "Work queue names by logical name, for scaling metrics."
  type        = map(string)
}

variable "kms_key_arn" {
  description = "Key that encrypts tables and queues."
  type        = string
}

variable "api" {
  description = "Sizing of the API service."
  type = object({
    cpu               = number
    memory            = number
    min_tasks         = number
    max_tasks         = number
    requests_per_task = number
  })
}

variable "worker" {
  description = "Sizing of the worker service."
  type = object({
    cpu              = number
    memory           = number
    min_tasks        = number
    max_tasks        = number
    backlog_per_task = number
  })
}

variable "waf_requests_per_5_minutes" {
  description = "Requests a single IP may send in 5 minutes before the WAF blocks it."
  type        = number
}

variable "log_retention_days" {
  description = "Days to keep application logs."
  type        = number
}

variable "deletion_protection" {
  description = "Protect the load balancer from deletion."
  type        = bool
}
