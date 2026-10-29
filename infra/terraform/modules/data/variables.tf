variable "name" {
  description = "Prefix for every resource name, for example ticketing-dev."
  type        = string
}

variable "deletion_protection" {
  description = "Protect tables from deletion. Always true in production."
  type        = bool
}

variable "queue_names" {
  description = "One queue (and its DLQ) per step of the purchase."
  type        = list(string)
  default     = ["order-placed", "order-confirmation"]
}
