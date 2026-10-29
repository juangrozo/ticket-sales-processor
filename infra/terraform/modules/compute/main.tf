data "aws_region" "current" {}

# ---------------------------------------------------------------------------------------------
# Image registry
# ---------------------------------------------------------------------------------------------

resource "aws_ecr_repository" "app" {
  name = var.name
  # A tag always points to the same image: what was tested is what runs
  image_tag_mutability = "IMMUTABLE"

  image_scanning_configuration {
    scan_on_push = true
  }
}

resource "aws_ecr_lifecycle_policy" "app" {
  repository = aws_ecr_repository.app.name
  policy = jsonencode({
    rules = [{
      rulePriority = 1
      description  = "Keep the last 20 images"
      selection    = { tagStatus = "any", countType = "imageCountMoreThan", countNumber = 20 }
      action       = { type = "expire" }
    }]
  })
}

# ---------------------------------------------------------------------------------------------
# Secret: the admin key is created empty here and its value is set out of band
# (aws secretsmanager put-secret-value), so it never appears in code or in the Terraform state.
# ---------------------------------------------------------------------------------------------

resource "aws_secretsmanager_secret" "admin_api_key" {
  name                    = "${var.name}/admin-api-key"
  recovery_window_in_days = 7
}

# ---------------------------------------------------------------------------------------------
# Load balancer and WAF
# ---------------------------------------------------------------------------------------------

resource "aws_security_group" "alb" {
  name        = "${var.name}-alb"
  description = "Public HTTPS entry point"
  vpc_id      = var.vpc_id

  ingress {
    description = "HTTPS"
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  ingress {
    description = "HTTP, only to redirect to HTTPS"
    from_port   = 80
    to_port     = 80
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  egress {
    description = "To the API tasks"
    from_port   = 8080
    to_port     = 8080
    protocol    = "tcp"
    cidr_blocks = [var.vpc_cidr]
  }
}

resource "aws_lb" "api" {
  name                       = "${var.name}-api"
  load_balancer_type         = "application"
  subnets                    = var.public_subnet_ids
  security_groups            = [aws_security_group.alb.id]
  drop_invalid_header_fields = true
  enable_deletion_protection = var.deletion_protection
}

resource "aws_lb_target_group" "api" {
  name        = "${var.name}-api"
  port        = 8080
  protocol    = "HTTP"
  target_type = "ip"
  vpc_id      = var.vpc_id
  # Short drain: requests are short and orders are already persisted when answered
  deregistration_delay = 30

  health_check {
    path                = "/actuator/health/readiness"
    healthy_threshold   = 2
    unhealthy_threshold = 3
    interval            = 15
  }
}

resource "aws_lb_listener" "https" {
  load_balancer_arn = aws_lb.api.arn
  port              = 443
  protocol          = "HTTPS"
  ssl_policy        = "ELBSecurityPolicy-TLS13-1-2-2021-06"
  certificate_arn   = var.certificate_arn

  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.api.arn
  }
}

resource "aws_lb_listener" "http_redirect" {
  load_balancer_arn = aws_lb.api.arn
  port              = 80
  protocol          = "HTTP"

  default_action {
    type = "redirect"

    redirect {
      port        = "443"
      protocol    = "HTTPS"
      status_code = "HTTP_301"
    }
  }
}

# First line against abuse: per-IP rate limit and AWS managed rules, before traffic reaches the
# tasks. The application keeps its own per-customer limit behind it.
resource "aws_wafv2_web_acl" "api" {
  name  = "${var.name}-api"
  scope = "REGIONAL"

  default_action {
    allow {}
  }

  rule {
    name     = "rate-limit-per-ip"
    priority = 1

    action {
      block {}
    }

    statement {
      rate_based_statement {
        limit              = var.waf_requests_per_5_minutes
        aggregate_key_type = "IP"
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "rate-limit-per-ip"
      sampled_requests_enabled   = true
    }
  }

  rule {
    name     = "aws-common-rules"
    priority = 2

    override_action {
      none {}
    }

    statement {
      managed_rule_group_statement {
        name        = "AWSManagedRulesCommonRuleSet"
        vendor_name = "AWS"
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "aws-common-rules"
      sampled_requests_enabled   = true
    }
  }

  visibility_config {
    cloudwatch_metrics_enabled = true
    metric_name                = "${var.name}-api"
    sampled_requests_enabled   = true
  }
}

resource "aws_wafv2_web_acl_association" "api" {
  resource_arn = aws_lb.api.arn
  web_acl_arn  = aws_wafv2_web_acl.api.arn
}

# ---------------------------------------------------------------------------------------------
# ECS: one image, two services
# ---------------------------------------------------------------------------------------------

resource "aws_ecs_cluster" "this" {
  name = var.name

  setting {
    name  = "containerInsights"
    value = "enhanced"
  }
}

resource "aws_ecs_cluster_capacity_providers" "this" {
  cluster_name       = aws_ecs_cluster.this.name
  capacity_providers = ["FARGATE", "FARGATE_SPOT"]
}

resource "aws_cloudwatch_log_group" "service" {
  for_each          = toset(["api", "worker"])
  name              = "/ecs/${var.name}/${each.key}"
  retention_in_days = var.log_retention_days
}

resource "aws_security_group" "task" {
  for_each    = toset(["api", "worker"])
  name        = "${var.name}-${each.key}"
  description = "Tasks of the ${each.key} role"
  vpc_id      = var.vpc_id

  # No internet route exists; this only allows reaching the VPC endpoints
  egress {
    description = "HTTPS to AWS services through VPC endpoints"
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = [var.vpc_cidr]
  }

  egress {
    description     = "DynamoDB and S3 gateway endpoints"
    from_port       = 443
    to_port         = 443
    protocol        = "tcp"
    prefix_list_ids = [data.aws_ec2_managed_prefix_list.dynamodb.id, data.aws_ec2_managed_prefix_list.s3.id]
  }
}

resource "aws_vpc_security_group_ingress_rule" "api_from_alb" {
  security_group_id            = aws_security_group.task["api"].id
  description                  = "Only the load balancer reaches the API"
  referenced_security_group_id = aws_security_group.alb.id
  from_port                    = 8080
  to_port                      = 8080
  ip_protocol                  = "tcp"
}

data "aws_ec2_managed_prefix_list" "dynamodb" {
  name = "com.amazonaws.${data.aws_region.current.region}.dynamodb"
}

data "aws_ec2_managed_prefix_list" "s3" {
  name = "com.amazonaws.${data.aws_region.current.region}.s3"
}

locals {
  image = "${aws_ecr_repository.app.repository_url}:${var.image_tag}"

  common_environment = [
    { name = "SPRING_PROFILES_ACTIVE", value = var.spring_profile },
    { name = "AWS_REGION", value = data.aws_region.current.region },
    { name = "DYNAMODB_TABLE_PREFIX", value = var.name },
    { name = "ORDER_QUEUE_URL", value = var.queue_urls["order-placed"] },
    { name = "CONFIRMATION_QUEUE_URL", value = var.queue_urls["order-confirmation"] },
    # Blank: use the regional AWS endpoint instead of a local emulator
    { name = "SQS_ENDPOINT", value = "" },
    { name = "JAVA_OPTS", value = "-XX:MaxRAMPercentage=70" },
  ]

  roles = {
    api = {
      cpu     = var.api.cpu
      memory  = var.api.memory
      secrets = [{ name = "ADMIN_API_KEY", valueFrom = aws_secretsmanager_secret.admin_api_key.arn }]
      ports   = [{ containerPort = 8080, protocol = "tcp" }]
    }
    worker = {
      cpu     = var.worker.cpu
      memory  = var.worker.memory
      secrets = []
      ports   = []
    }
  }
}

resource "aws_ecs_task_definition" "service" {
  for_each                 = local.roles
  family                   = "${var.name}-${each.key}"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = each.value.cpu
  memory                   = each.value.memory
  execution_role_arn       = aws_iam_role.execution.arn
  task_role_arn            = aws_iam_role.task[each.key].arn

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "ARM64" # Graviton: cheaper per vCPU for the same JVM workload
  }

  volume {
    name = "tmp"
  }

  container_definitions = jsonencode([{
    name                   = each.key
    image                  = local.image
    essential              = true
    readonlyRootFilesystem = true
    portMappings           = each.value.ports
    environment            = concat(local.common_environment, [{ name = "APP_ROLE", value = each.key }])
    secrets                = each.value.secrets
    mountPoints            = [{ sourceVolume = "tmp", containerPath = "/tmp", readOnly = false }]
    healthCheck = {
      command     = ["CMD-SHELL", "wget -qO- http://localhost:8080/actuator/health || exit 1"]
      interval    = 15
      timeout     = 5
      retries     = 3
      startPeriod = 60
    }
    logConfiguration = {
      logDriver = "awslogs"
      options = {
        awslogs-group         = aws_cloudwatch_log_group.service[each.key].name
        awslogs-region        = data.aws_region.current.region
        awslogs-stream-prefix = each.key
      }
    }
  }])
}

resource "aws_ecs_service" "api" {
  name                   = "${var.name}-api"
  cluster                = aws_ecs_cluster.this.id
  task_definition        = aws_ecs_task_definition.service["api"].arn
  desired_count          = var.api.min_tasks
  launch_type            = "FARGATE"
  enable_execute_command = false

  network_configuration {
    subnets          = var.private_subnet_ids
    security_groups  = [aws_security_group.task["api"].id]
    assign_public_ip = false
  }

  load_balancer {
    target_group_arn = aws_lb_target_group.api.arn
    container_name   = "api"
    container_port   = 8080
  }

  # A failing release is rolled back automatically instead of replacing healthy tasks
  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  lifecycle {
    ignore_changes = [desired_count]
  }

  depends_on = [aws_lb_listener.https]
}

resource "aws_ecs_service" "worker" {
  name                   = "${var.name}-worker"
  cluster                = aws_ecs_cluster.this.id
  task_definition        = aws_ecs_task_definition.service["worker"].arn
  desired_count          = var.worker.min_tasks
  enable_execute_command = false

  # Spot is safe for the worker: an interrupted task leaves its message in the queue, which another
  # task picks up after the visibility timeout. One on-demand task keeps a baseline.
  capacity_provider_strategy {
    capacity_provider = "FARGATE"
    base              = 1
    weight            = 1
  }

  capacity_provider_strategy {
    capacity_provider = "FARGATE_SPOT"
    weight            = 3
  }

  network_configuration {
    subnets          = var.private_subnet_ids
    security_groups  = [aws_security_group.task["worker"].id]
    assign_public_ip = false
  }

  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  lifecycle {
    ignore_changes = [desired_count]
  }

  depends_on = [aws_ecs_cluster_capacity_providers.this]
}

# ---------------------------------------------------------------------------------------------
# Autoscaling: each role scales on its own signal
# ---------------------------------------------------------------------------------------------

resource "aws_appautoscaling_target" "service" {
  for_each = {
    api    = { service = aws_ecs_service.api.name, min = var.api.min_tasks, max = var.api.max_tasks }
    worker = { service = aws_ecs_service.worker.name, min = var.worker.min_tasks, max = var.worker.max_tasks }
  }
  service_namespace  = "ecs"
  resource_id        = "service/${aws_ecs_cluster.this.name}/${each.value.service}"
  scalable_dimension = "ecs:service:DesiredCount"
  min_capacity       = each.value.min
  max_capacity       = each.value.max
}

# API: requests per task behind the load balancer
resource "aws_appautoscaling_policy" "api_requests" {
  name               = "${var.name}-api-requests"
  policy_type        = "TargetTrackingScaling"
  service_namespace  = "ecs"
  resource_id        = aws_appautoscaling_target.service["api"].resource_id
  scalable_dimension = aws_appautoscaling_target.service["api"].scalable_dimension

  target_tracking_scaling_policy_configuration {
    target_value = var.api.requests_per_task

    predefined_metric_specification {
      predefined_metric_type = "ALBRequestCountPerTarget"
      resource_label         = "${aws_lb.api.arn_suffix}/${aws_lb_target_group.api.arn_suffix}"
    }
  }
}

# Worker: messages waiting in the reservation queue
resource "aws_appautoscaling_policy" "worker_backlog" {
  name               = "${var.name}-worker-backlog"
  policy_type        = "TargetTrackingScaling"
  service_namespace  = "ecs"
  resource_id        = aws_appautoscaling_target.service["worker"].resource_id
  scalable_dimension = aws_appautoscaling_target.service["worker"].scalable_dimension

  target_tracking_scaling_policy_configuration {
    target_value = var.worker.backlog_per_task

    customized_metric_specification {
      metric_name = "ApproximateNumberOfMessagesVisible"
      namespace   = "AWS/SQS"
      statistic   = "Average"

      dimensions {
        name  = "QueueName"
        value = var.queue_names["order-placed"]
      }
    }
  }
}
