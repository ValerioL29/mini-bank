package com.valerioL29.bank.actors

import akka.NotUsed
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, ActorSystem, Behavior, Scheduler}
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}
import org.slf4j.Logger

import java.util.UUID
import scala.util.Failure

object Bank {

  // commands = messages
  import PersistentBankAccount.Command
  import PersistentBankAccount.Command._
  import PersistentBankAccount.Response._

  // events
  sealed trait Event
  case class BankAccountCreated(id: String) extends Event

  // state
  case class State(accounts: Map[String, ActorRef[Command]])

  // command handler
  def commandHandler(context: ActorContext[Command]): (State, Command) => Effect[Event, State] = (state: State, command: Command) =>
    command match {
      case createCommand @ CreateBankAccount(_, _, _, _) =>
        val id: String = UUID.randomUUID().toString
        val newBankAccount: ActorRef[Command] = context.spawn(PersistentBankAccount(id), id)
        Effect
          .persist(BankAccountCreated(id))
          .thenReply(newBankAccount)((_: State) => createCommand)
      case updateCommand @ UpdateBalance(id, _, _, replyTo) =>
        state.accounts.get(id) match {
          case Some(account) =>
            Effect.reply(account)(updateCommand)
          case None =>
            Effect.reply(replyTo)(BankAccountBalanceUpdatedResponse(Failure(new RuntimeException("Bank account cannot be found")))) // failed account search
        }
      case getCommand @ GetBankAccount(id, replyTo) =>
        state.accounts.get(id) match {
          case Some(account) =>
            Effect.reply(account)(getCommand)
          case None =>
            Effect.reply(replyTo)(GetBankAccountResponse(None)) // failed search
        }
    }


  // event handler
  def eventHandler(context: ActorContext[Command]): (State, Event) => State = (state: State, event: Event) => {
    event match {
      case BankAccountCreated(id) =>
        val account: ActorRef[Command] = context
          .child(id) // exists after command handler, does NOT exist in the recovery mode
          .getOrElse(context.spawn(PersistentBankAccount(id), id))
          .asInstanceOf[ActorRef[Command]]
        state.copy(state.accounts + (id -> account))

    }
  }

  // behaviour
  def apply(): Behavior[Command] = Behaviors.setup { context: ActorContext[Command] =>
    EventSourcedBehavior[Command, Event, State](
      persistenceId = PersistenceId.ofUniqueId("bank"),
      emptyState = State(Map()),
      commandHandler = commandHandler(context),
      eventHandler = eventHandler(context)
    )
  }
}

object BankPlayground {
  import PersistentBankAccount.Command._
  import PersistentBankAccount.Response
  import PersistentBankAccount.Response._

  def main(args: Array[String]): Unit = {
    val rootBehavior: Behavior[NotUsed] = Behaviors.setup { context: ActorContext[NotUsed] =>
      val bank: ActorRef[PersistentBankAccount.Command] = context.spawn(Bank(), "bank")
      val logger: Logger = context.log

      val responseHandler: ActorRef[Response] = context.spawn(Behaviors.receiveMessage[Response]{
        case BankAccountCreatedResponse(id) =>
          logger.info(s"successfully created bank account with id: $id")
          Behaviors.same
        case GetBankAccountResponse(maybeBankAccount) =>
          logger.info(s"Account details: $maybeBankAccount")
          Behaviors.same
      }, "replyHandler")

      // ask pattern
      import akka.util.Timeout

      import scala.concurrent.ExecutionContext
      import scala.concurrent.duration._

      implicit val timeout: Timeout = Timeout(2.seconds)
      implicit val scheduler: Scheduler = context.system.scheduler
      implicit val ec: ExecutionContext = context.executionContext

      bank ! CreateBankAccount("ken", "USD", 10, responseHandler)
      bank ! GetBankAccount("5f60615e-bede-4c51-bf1b-0675566d8e83", responseHandler)

      Behaviors.empty
    }

    val system: ActorSystem[NotUsed] = ActorSystem(rootBehavior, "BankDemo")
  }
}
