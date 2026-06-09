variable "region" {
  description = "AWS region"
  type        = string
  default     = "eu-west-2"
}

variable "project" {
  description = "Resource name prefix"
  type        = string
  default     = "trustledger"
}

variable "db_instance_class" {
  description = "RDS instance class"
  type        = string
  default     = "db.t3.medium"
}

variable "db_username" {
  description = "Master DB username"
  type        = string
  default     = "trustledger"
}

variable "multi_az" {
  description = "Multi-AZ RDS for HA (enable in production)"
  type        = bool
  default     = true
}

variable "tags" {
  description = "Extra resource tags"
  type        = map(string)
  default     = {}
}
