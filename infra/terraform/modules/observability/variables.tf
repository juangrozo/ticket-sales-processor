variable "name" {
  description = "Prefix for every resource name, for example ticketing-dev."
  type        = string
}

variable "alarm_emails" {
  description = "Addresses that receive alarm notifications."
  type        = list(string)
  default     = []
}

variable "queue_names" {
  description = "Work queue names by logical name."
  type        = map(string)
}

variable "dlq_names" {
  description = "Dead-letter queue names by logical name."
  type        = map(string)
}

variable "table_names" {
  description = "Table names by logical name."
  type        = map(string)
}

variable "alb_arn_suffix" {
  description = "Load balancer identifier for metrics."
  type        = string
}

variable "target_group_arn_suffix" {
  description = "Target group identifier for metrics."
  type        = string
}

variable "max_message_age_seconds" {
  description = "Oldest acceptable message age before alarming."
  type        = number
  default     = 120
}

variable "max_5xx_per_minute" {
  description = "Server errors per minute tolerated before alarming."
  type        = number
  default     = 5
}

variable "max_p99_latency_seconds" {
  description = "p99 API latency tolerated before alarming."
  type        = number
  default     = 0.5
}

variable "monthly_budget_usd" {
  description = "Monthly spend limit; the alarm fires at 80% forecast."
  type        = number
}
