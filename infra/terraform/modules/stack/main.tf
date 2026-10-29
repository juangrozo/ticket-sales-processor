# Everything one environment needs. Environments differ only in the values they pass.

module "network" {
  source     = "../network"
  name       = var.name
  cidr_block = var.vpc_cidr
  az_count   = var.az_count
}

module "data" {
  source              = "../data"
  name                = var.name
  deletion_protection = var.deletion_protection
}

module "compute" {
  source                     = "../compute"
  name                       = var.name
  vpc_id                     = module.network.vpc_id
  vpc_cidr                   = var.vpc_cidr
  public_subnet_ids          = module.network.public_subnet_ids
  private_subnet_ids         = module.network.private_subnet_ids
  certificate_arn            = var.certificate_arn
  image_tag                  = var.image_tag
  spring_profile             = var.spring_profile
  table_arns                 = module.data.table_arns
  queue_arns                 = module.data.queue_arns
  queue_urls                 = module.data.queue_urls
  queue_names                = module.data.queue_names
  kms_key_arn                = module.data.kms_key_arn
  api                        = var.api
  worker                     = var.worker
  waf_requests_per_5_minutes = var.waf_requests_per_5_minutes
  log_retention_days         = var.log_retention_days
  deletion_protection        = var.deletion_protection
}

module "observability" {
  source                  = "../observability"
  name                    = var.name
  alarm_emails            = var.alarm_emails
  queue_names             = module.data.queue_names
  dlq_names               = module.data.dlq_names
  table_names             = module.data.table_names
  alb_arn_suffix          = module.compute.alb_arn_suffix
  target_group_arn_suffix = module.compute.target_group_arn_suffix
  monthly_budget_usd      = var.monthly_budget_usd
}
