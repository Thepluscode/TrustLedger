locals {
  tags = merge({
    Project   = var.project
    ManagedBy = "terraform"
  }, var.tags)
}

resource "random_password" "db" {
  length  = 32
  special = false
}

# --- Managed PostgreSQL (encrypted, backed up, deletion-protected) ---
resource "aws_db_instance" "postgres" {
  identifier                = "${var.project}-pg"
  engine                    = "postgres"
  engine_version            = "16"
  instance_class            = var.db_instance_class
  allocated_storage         = 50
  max_allocated_storage     = 200
  storage_encrypted         = true
  db_name                   = "trustledger"
  username                  = var.db_username
  password                  = random_password.db.result
  multi_az                  = var.multi_az
  backup_retention_period   = 14
  deletion_protection       = true
  skip_final_snapshot       = false
  final_snapshot_identifier = "${var.project}-pg-final"
  tags                      = local.tags
}

# --- Evidence object storage (versioned, encrypted, private; Object Lock target in prod) ---
resource "aws_s3_bucket" "evidence" {
  bucket = "${var.project}-evidence-${var.region}"
  tags   = local.tags
}

resource "aws_s3_bucket_versioning" "evidence" {
  bucket = aws_s3_bucket.evidence.id
  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_public_access_block" "evidence" {
  bucket                  = aws_s3_bucket.evidence.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_server_side_encryption_configuration" "evidence" {
  bucket = aws_s3_bucket.evidence.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "aws:kms"
    }
  }
}

# --- Container registries ---
resource "aws_ecr_repository" "backend" {
  name = "${var.project}-backend"
  image_scanning_configuration {
    scan_on_push = true
  }
  tags = local.tags
}

resource "aws_ecr_repository" "frontend" {
  name = "${var.project}-frontend"
  image_scanning_configuration {
    scan_on_push = true
  }
  tags = local.tags
}

# --- App secrets (consumed by the cluster via External Secrets Operator) ---
resource "aws_secretsmanager_secret" "app" {
  name = "${var.project}/app"
  tags = local.tags
}

resource "aws_secretsmanager_secret_version" "app" {
  secret_id = aws_secretsmanager_secret.app.id
  secret_string = jsonencode({
    DATABASE_PASSWORD = random_password.db.result
    JWT_SECRET        = "SET_VIA_ROTATION"
  })
}
