variable "name" {
  description = "Prefix for every resource name, for example ticketing-dev."
  type        = string
}

variable "cidr_block" {
  description = "CIDR of the VPC. Each environment gets its own, non-overlapping range."
  type        = string
}

variable "az_count" {
  description = "Number of availability zones to spread subnets across."
  type        = number
  default     = 2

  validation {
    condition     = var.az_count >= 2
    error_message = "At least two availability zones are required for high availability."
  }
}
