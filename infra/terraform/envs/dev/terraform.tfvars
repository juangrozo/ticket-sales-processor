environment         = "dev"
owner               = "ticketing-team"
cost_center         = "ticketing-dev"
vpc_cidr            = "10.10.0.0/16"
certificate_arn     = "arn:aws:acm:us-east-1:111111111111:certificate/replace-me"
image_tag           = "replace-with-commit-sha"
spring_profile      = "dev"
deletion_protection = false

# Small and cheap: one API task, one worker, short log retention
api                        = { cpu = 512, memory = 1024, min_tasks = 1, max_tasks = 2, requests_per_task = 500 }
worker                     = { cpu = 512, memory = 1024, min_tasks = 1, max_tasks = 2, backlog_per_task = 100 }
waf_requests_per_5_minutes = 1000
log_retention_days         = 7
monthly_budget_usd         = 150
