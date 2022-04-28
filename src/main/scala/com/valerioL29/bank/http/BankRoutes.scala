package com.valerioL29.bank.http

import akka.actor.typed._
import akka.actor.typed.scaladsl.AskPattern._
import akka.util.Timeout
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.Location
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import com.valerioL29.bank.actors.PersistentBankAccount.{Command, Response}
import com.valerioL29.bank.actors.PersistentBankAccount.Command._
import com.valerioL29.bank.actors.PersistentBankAccount.Response._
import io.circe.generic.auto._
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

case class BankAccountCreationRequest(user: String, currency: String, balance: Double){
  def toCommand(replyTo: ActorRef[Response]): Command = CreateBankAccount(user, currency, balance, replyTo)
}

case class BankAccountUpdateRequest(currency: String, amount: Double){
  def toCommand(id: String, replyTo: ActorRef[Response]): Command = UpdateBalance(id, currency, amount, replyTo)
}

case class FailureResponse(reason: String)

class BankRoutes(bank: ActorRef[Command])(implicit system: ActorSystem[_]) {
  implicit val timeout: Timeout = Timeout(5.seconds)

  def createBankAccount(request: BankAccountCreationRequest): Future[Response] =
    bank.ask(replyTo => request.toCommand(replyTo))
  def getBankAccount(id: String): Future[Response] =
    bank.ask(replyTo => GetBankAccount(id, replyTo))
  def updateBankAccount(id: String, request: BankAccountUpdateRequest): Future[Response] =
    bank.ask(replyTo => request.toCommand(id, replyTo))

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
   *     3) TODO 400 bad request if something wrong
   */

  val routes: Route = pathPrefix("bank") {
    pathEndOrSingleSlash {
      post {
        // parse the payload
        entity(as[BankAccountCreationRequest]) { request: BankAccountCreationRequest =>
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
    } ~
    path(Segment) { id =>
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
        // 5. TODO validate the request
        entity(as[BankAccountUpdateRequest]) { request =>
          onSuccess(updateBankAccount(id, request)) {
            case BankAccountBalanceUpdatedResponse(Some(account)) => complete(account)
            case BankAccountBalanceUpdatedResponse(None) =>
              complete(StatusCodes.NotFound, FailureResponse(s"Bank account $id cannot be found."))
          }
        }
      }
    }
  }


}
