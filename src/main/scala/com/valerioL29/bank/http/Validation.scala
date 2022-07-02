package com.valerioL29.bank.http

import cats.data.ValidatedNel
import cats.implicits.catsSyntaxValidatedId

object Validation {

  // field must be present
  trait Required[A] extends (A => Boolean)
  // minimum value
  trait Minimum[A] extends ((A, Double) => Boolean) // for numerical fields
  trait MinimumAbs[A] extends ((A, Double) => Boolean) // for numerical fields

  // TC instances - TC is Type Class
  implicit val requiredString: Required[String] = (_: String).nonEmpty
  implicit val minimumInt: Minimum[Int] = (_: Int) >= (_: Double)
  implicit val minimumDouble: Minimum[Double] = (_: Double) >= (_: Double)
  implicit val minimumIntAbs: MinimumAbs[Int] = Math.abs(_: Int) >= (_: Double)
  implicit val minimumDoubleAbs: MinimumAbs[Double] = Math.abs(_: Double) >= (_: Double)

  // usage
  def required[A](value: A)(implicit req: Required[A]): Boolean = req(value)
  def minimum[A](value: A, threshold: Double)(implicit min: Minimum[A]): Boolean = min(value, threshold)
  def minimumAbs[A](value: A, threshold: Double)(implicit min: MinimumAbs[A]): Boolean = min(value, threshold)

  // Validated
  type ValidationResult[A] = ValidatedNel[ValidationFailure, A]

  // validation failures
  trait ValidationFailure {
    def errorMessage: String
  }
  case class EmptyField(fieldName: String) extends ValidationFailure {
    override def errorMessage: String = s"$fieldName is empty"
  }
  case class NegativeValue(fieldName: String) extends ValidationFailure {
    override def errorMessage: String = s"$fieldName is negative"
  }
  case class BelowMinimumValue(fieldName: String, minimum: Double) extends ValidationFailure {
    override def errorMessage: String = s"$fieldName is below the minimum threshold $minimum"
  }

  // "main" API
  def validateMinimum[A: Minimum](value: A, threshold: Double, fieldName: String): ValidationResult[A] = {
    if (minimum(value, threshold)) value.validNel
    else if (threshold == 0) NegativeValue(fieldName).invalidNel
    else BelowMinimumValue(fieldName, threshold).invalidNel
  }

  def validateMinimumAbs[A: MinimumAbs](value: A, threshold: Double, fieldName: String): ValidationResult[A] = {
    if (minimumAbs(value, threshold)) value.validNel
    else BelowMinimumValue(fieldName, threshold).invalidNel
  }

  def validateRequired[A: Required](value: A, fieldName: String): ValidationResult[A] = {
    if (required(value)) value.validNel
    else EmptyField(fieldName).invalidNel
  }

  // general TC for requests
  trait Validator[A] {
    def validate(value: A): ValidationResult[A]
  }

  def validateEntity[A](value: A)(implicit validator: Validator[A]): ValidationResult[A] =
    validator.validate(value)
}
