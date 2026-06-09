output "db_endpoint" {
  description = "PostgreSQL connection endpoint"
  value       = aws_db_instance.postgres.endpoint
}

output "evidence_bucket" {
  description = "Evidence object-storage bucket name"
  value       = aws_s3_bucket.evidence.bucket
}

output "ecr_backend_url" {
  value = aws_ecr_repository.backend.repository_url
}

output "ecr_frontend_url" {
  value = aws_ecr_repository.frontend.repository_url
}

output "app_secret_arn" {
  description = "Secrets Manager ARN consumed by the cluster"
  value       = aws_secretsmanager_secret.app.arn
}
