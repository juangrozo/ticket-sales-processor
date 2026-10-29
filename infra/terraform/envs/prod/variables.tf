variable "region" {
  description = "AWS region."
  type        = string
  default     = "us-east-1"
}

variable "project" {
  description = "Project name, first part of every resource name."
  type        = string
  default     = "ticketing"
}

variable "environment" {
  description = "Environment name."
  type        = string
}

variable "owner" {
  description = "Team that owns the resources."
  type        = string
}

variable "cost_center" {
  description = "Cost center the spend is charged to."
  type        = string
}

variable "vpc_cidr" {
  description = "CIDR of the environment VPC."
  type        = string
}

variable "certificate_arn" {
  description = "ACM certificate for the HTTPS listener."
  type        = string
}

variable "image_tag" {
  description = "Immutable image tag to deploy, set by CI."
  type        = string
}

variable "spring_profile" {
  description = "Spring profile: dev, cer or pdn."
  type        = string
}

variable "deletion_protection" {
  description = "Protect stateful resources from deletion."
  type        = bool
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
  description = "Per-IP request limit enforced by the WAF."
  type        = number
}

variable "log_retention_days" {
  description = "Days to keep application logs."
  type        = number
}

variable "alarm_emails" {
  description = "Addresses that receive alarms."
  type        = list(string)
  default     = []
}

variable "monthly_budget_usd" {
  description = "Monthly spend limit."
  type        = number
}
