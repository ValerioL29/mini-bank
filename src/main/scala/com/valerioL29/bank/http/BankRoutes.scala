package com.valerioL29.bank.http

import akka.actor.typed._
import akka.actor.typed.scaladsl.AskPattern._
import akka.util.Timeout
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.Location
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import cats.data.Validated.{Invalid, Valid}
import cats.implicits.{catsSyntaxTuple2Semigroupal, catsSyntaxTuple3Semigroupal}
import com.valerioL29.bank.actors.PersistentBankAccount.{Command, Response}
import com.valerioL29.bank.actors.PersistentBankAccount.Command._
import com.valerioL29.bank.actors.PersistentBankAccount.Response._
import com.valerioL29.bank.http.Validation._
import io.circe.generic.auto._
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.util.{Failure, Success}

case class BankAccountCreationRequest(user: String, currency: String, balance: Double){
  def toCommand(replyTo: ActorRef[Response]): Command = CreateBankAccount(user, currency, balance, replyTo)
}

object BankAccountCreationRequest {
  implicit val validator: Validator[BankAccountCreationRequest] = (request: BankAccountCreationRequest) => {
    val userValidation: ValidationResult[String] = validateRequired(request.user, "user")
    val currencyValidation: ValidationResult[String] = validateRequired(request.currency, "currency")
    val balanceValidation: ValidationResult[Double] =
      validateMinimum(request.balance, 0d, "balance")
        .combine {
          validateMinimumAbs(request.balance, 0.01d, "balance")
        }

    (userValidation, currencyValidation, balanceValidation).mapN(BankAccountCreationRequest.apply)
  }
}

case class BankAccountUpdateRequest(currency: String, amount: Double){
  def toCommand(id: String, replyTo: ActorRef[Response]): Command = UpdateBalance(id, currency, amount, replyTo)
}

object BankAccountUpdateRequest {
  implicit val validator: Validator[BankAccountUpdateRequest] = (request: BankAccountUpdateRequest) => {
    val currencyValidation: ValidationResult[String] = validateRequired(request.currency, "currency")
    val balanceValidation: ValidationResult[Double] =
      validateMinimumAbs(request.amount, 0.01d, "amount")

    (currencyValidation, balanceValidation).mapN(BankAccountUpdateRequest.apply)
  }
}

case class FailureResponse(reason: String)

class BankRoutes(bank: ActorRef[Command])(implicit system: ActorSystem[_]) {
  implicit val timeout: Timeout = Timeout(5.seconds)

  def createBankAccount(request: BankAccountCreationRequest): Future[Response] =
    bank.ask((replyTo: ActorRef[Response]) => request.toCommand(replyTo))

  def getBankAccount(id: String): Future[Response] =
    bank.ask((replyTo: ActorRef[Response]) => GetBankAccount(id, replyTo))

  def updateBankAccount(id: String, request: BankAccountUpdateRequest): Future[Response] =
    bank.ask((replyTo: ActorRef[Response]) => request.toCommand(id, replyTo))

  def validateRequest[R: Validator](request: R)(routeIfValid: Route): Route =
    validateEntity(request) match {
      case Valid(_) =>
        routeIfValid
      case Invalid(failures) =>
        complete(
          StatusCodes.BadRequest,
          FailureResponse(
            failures
              .toList
              .map((_: ValidationFailure).errorMessage)
              .mkString(", ")
          )
        )
    }
  /**
   * POST /bank/
   *   Payload: bank account creation request as JSON
   *   Response:
   *     201 Created
   *     Location: /bank/uuid
   *
   * GET /bank/uuid
   *   Response:
   *     200 OK
   *     JSON representation of bank account profile
   *
   * PUT /bank/uuid
   *   Payload: (currency, amount) as JSON
   *   Response:
   *     1) 200 OK
   *       Payload: new bank details as JSON
   *     2) 404 Not found
   *     3) 400 bad request if something wrong
   */

  val routes: Route = pathPrefix("bank") {
    pathEndOrSingleSlash {
      post {
        // parse the payload
        entity(as[BankAccountCreationRequest]) { request: BankAccountCreationRequest =>
          validateRequest(request) {
            // 1. convert the request into a Command for the bank actor
            // 2. send the command to the bank
            // 3. expect a reply
            // 4. send back an HTTP response
            onSuccess(createBankAccount(request)) {
              case BankAccountCreatedResponse(id) =>
                respondWithHeader(Location(s"/bank/$id")) {
                  complete(StatusCodes.Created)
                }
            }
          }
        }
      }
    } ~
    path(Segment) { id: String =>
      get {
        // 1. send command to the bank
        // 2. expect a reply
        // 3. send back the HTTP response
        onSuccess(getBankAccount(id)) {
          case GetBankAccountResponse(Some(account)) => complete(account) // 200 OK
          case GetBankAccountResponse(None) =>
            complete(StatusCodes.NotFound, FailureResponse(s"Bank account $id cannot be found."))
        }
      } ~
      put {
        // 1. parse the request to a Command
        // 2. send the command to the bank
        // 3. expect a reply
        // 4. send back an HTTP response
        // 5. validate the request
        entity(as[BankAccountUpdateRequest]) { request: BankAccountUpdateRequest =>
          validateRequest(request) {
            onSuccess(updateBankAccount(id, request)) {
              case BankAccountBalanceUpdatedResponse(Success(account)) =>
                complete(account)
              case BankAccountBalanceUpdatedResponse(Failure(ex)) =>
                complete(StatusCodes.NotFound, FailureResponse(s"${ex.getMessage}"))
            }
          }
        }
      }
    }
  }


}
