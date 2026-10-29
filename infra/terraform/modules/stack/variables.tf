variable "name" {
  description = "Prefix for every resource name: <project>-<environment>."
  type        = string
}

variable "vpc_cidr" {
  description = "CIDR of the environment VPC; environments must not overlap."
  type        = string
}

variable "az_count" {
  description = "Availability zones to use."
  type        = number
  default     = 2
}

variable "certificate_arn" {
  description = "ACM certificate for the HTTPS listener."
  type        = string
}

variable "image_tag" {
  description = "Immutable image tag to deploy."
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
