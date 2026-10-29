# One task role per application role. The API cannot consume queues and the worker cannot write
# idempotency keys or scan events: a compromised task only gets what its role needs.

data "aws_iam_policy_document" "ecs_tasks_assume" {
  statement {
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }
  }
}

# Execution role: used by ECS itself to pull the image, write logs and read the admin secret
resource "aws_iam_role" "execution" {
  name               = "${var.name}-task-execution"
  assume_role_policy = data.aws_iam_policy_document.ecs_tasks_assume.json
}

resource "aws_iam_role_policy_attachment" "execution" {
  role       = aws_iam_role.execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

data "aws_iam_policy_document" "execution_secrets" {
  statement {
    actions   = ["secretsmanager:GetSecretValue"]
    resources = [aws_secretsmanager_secret.admin_api_key.arn]
  }
}

resource "aws_iam_role_policy" "execution_secrets" {
  name   = "read-admin-api-key"
  role   = aws_iam_role.execution.id
  policy = data.aws_iam_policy_document.execution_secrets.json
}

locals {
  tables = var.table_arns
  # Tables and queues are encrypted with the data key; it may only be used through those services
  kms_statement = {
    actions   = ["kms:Decrypt", "kms:GenerateDataKey"]
    resources = [var.kms_key_arn]
    services  = ["dynamodb.${data.aws_region.current.region}.amazonaws.com", "sqs.${data.aws_region.current.region}.amazonaws.com"]
  }
}

data "aws_iam_policy_document" "api" {
  statement {
    sid       = "ReadModel"
    actions   = ["dynamodb:GetItem", "dynamodb:Query"]
    resources = [local.tables.events, local.tables.orders, local.tables.tickets, local.tables.idempotency]
  }

  statement {
    sid       = "ListEvents"
    actions   = ["dynamodb:Scan"]
    resources = [local.tables.events]
  }

  # Creating orders, confirming them and granting complimentary tickets are TransactWriteItems
  # calls: each item inside needs its own Put or Update permission
  statement {
    sid       = "TransactionalWrites"
    actions   = ["dynamodb:PutItem", "dynamodb:UpdateItem", "dynamodb:ConditionCheckItem"]
    resources = [local.tables.events, local.tables.orders, local.tables.tickets, local.tables.audit, local.tables.idempotency]
  }

  statement {
    sid       = "PublishOrders"
    actions   = ["sqs:SendMessage"]
    resources = values(var.queue_arns)
  }

  statement {
    sid       = "UseDataKey"
    actions   = local.kms_statement.actions
    resources = local.kms_statement.resources

    condition {
      test     = "StringEquals"
      variable = "kms:ViaService"
      values   = local.kms_statement.services
    }
  }
}

data "aws_iam_policy_document" "worker" {
  statement {
    sid       = "ReadModel"
    actions   = ["dynamodb:GetItem", "dynamodb:Query"]
    resources = [local.tables.events, local.tables.orders, "${local.tables.orders}/index/*", local.tables.tickets]
  }

  statement {
    sid       = "TransactionalWrites"
    actions   = ["dynamodb:PutItem", "dynamodb:UpdateItem", "dynamodb:ConditionCheckItem"]
    resources = [local.tables.events, local.tables.orders, local.tables.tickets, local.tables.audit]
  }

  statement {
    sid = "ConsumeQueues"
    actions = [
      "sqs:ReceiveMessage",
      "sqs:DeleteMessage",
      "sqs:ChangeMessageVisibility",
      "sqs:GetQueueAttributes",
    ]
    resources = values(var.queue_arns)
  }

  # The sweeper re-queues orders whose message was lost
  statement {
    sid       = "RequeueOrders"
    actions   = ["sqs:SendMessage"]
    resources = values(var.queue_arns)
  }

  statement {
    sid       = "UseDataKey"
    actions   = local.kms_statement.actions
    resources = local.kms_statement.resources

    condition {
      test     = "StringEquals"
      variable = "kms:ViaService"
      values   = local.kms_statement.services
    }
  }
}

resource "aws_iam_role" "task" {
  for_each           = toset(["api", "worker"])
  name               = "${var.name}-${each.key}-task"
  assume_role_policy = data.aws_iam_policy_document.ecs_tasks_assume.json
}

resource "aws_iam_role_policy" "task" {
  for_each = {
    api    = data.aws_iam_policy_document.api.json
    worker = data.aws_iam_policy_document.worker.json
  }
  name   = "${each.key}-least-privilege"
  role   = aws_iam_role.task[each.key].id
  policy = each.value
}
