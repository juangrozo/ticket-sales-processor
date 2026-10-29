environment         = "prod"
owner               = "ticketing-team"
cost_center         = "ticketing-prod"
vpc_cidr            = "10.20.0.0/16"
certificate_arn     = "arn:aws:acm:us-east-1:222222222222:certificate/replace-me"
image_tag           = "replace-with-commit-sha"
spring_profile      = "pdn"
deletion_protection = true

# Two API tasks minimum (one per AZ); the worker scales out with the queue during releases
api                        = { cpu = 1024, memory = 2048, min_tasks = 2, max_tasks = 20, requests_per_task = 500 }
worker                     = { cpu = 1024, memory = 2048, min_tasks = 2, max_tasks = 30, backlog_per_task = 100 }
waf_requests_per_5_minutes = 2000
log_retention_days         = 30
alarm_emails               = ["oncall@example.com"]
monthly_budget_usd         = 2000
