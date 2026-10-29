provider "aws" {
  region = var.region

  # Every resource is tagged for cost allocation and ownership without repeating it anywhere
  default_tags {
    tags = {
      Project     = var.project
      Environment = var.environment
      Stack       = "${var.project}-${var.environment}"
      Owner       = var.owner
      CostCenter  = var.cost_center
      ManagedBy   = "terraform"
    }
  }
}

module "stack" {
  source                     = "../../modules/stack"
  name                       = "${var.project}-${var.environment}"
  vpc_cidr                   = var.vpc_cidr
  certificate_arn            = var.certificate_arn
  image_tag                  = var.image_tag
  spring_profile             = var.spring_profile
  deletion_protection        = var.deletion_protection
  api                        = var.api
  worker                     = var.worker
  waf_requests_per_5_minutes = var.waf_requests_per_5_minutes
  log_retention_days         = var.log_retention_days
  alarm_emails               = var.alarm_emails
  monthly_budget_usd         = var.monthly_budget_usd
}
