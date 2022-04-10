package com.valerioL29.bank.actors

import akka.actor.typed.{ActorRef, Behavior}
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}

/**
 * A single bank account
 */
object PersistentBankAccount {
  /**
   * - fault tolerance
   * - auditing
   */
  // commands = messages
  sealed trait Command
  object Command {
    case class CreateBankAccount(user: String, currency: String, initialBalance: Double, replyTo: ActorRef[Response]) extends Command
    case class UpdateBalance(id: String, currency: String, amount: Double /* can be negative*/, replyTo: ActorRef[Response]) extends Command
    case class GetBankAccount(id: String, replyTo: ActorRef[Response]) extends Command
  }

  // events = to persist to Cassandra
  trait Event
  case class BankAccountCreated(bankAccount: BankAccount) extends Event
  case class BalanceUpdated(amount: Double) extends Event

  // state
  case class BankAccount(id: String, user: String, currency: String, balance: Double)

  // responses
  sealed trait Response
  object Response {
    case class BankAccountCreatedResponse(id: String) extends Response
    case class BankAccountBalanceUpdatedResponse(maybeBankAccount: Option[BankAccount]) extends Response
    case class GetBankAccountResponse(maybeBankAccount: Option[BankAccount]) extends Response
  }

  /**
   * Persistent Actors
   * - command handler = message handler => persist an event
   * - event handler => update state
   * - state
   */
  import Command._
  import Response._
  val commandHandler: (BankAccount, Command) => Effect[Event, BankAccount] = (state, command) => command match {
    case CreateBankAccount(user, currency, initialBalance, bank) =>
      val id = state.id

      /**
       * 1. bank creates me
       * 2. bank sends me CreateBankAccount
       * 3. I persist BankAccountCreated
       * 4. I update my state
       * 5. reply back to bank with the BankAccountCreatedResponse
       * (then bank surfaces the response to the HTTP server)
       */
      Effect
        .persist(BankAccountCreated(BankAccount(id, user, currency, initialBalance))) // persisted into Cassandra
        .thenReply(bank)(_ => BankAccountCreatedResponse(id))
    case UpdateBalance(_, _, amount, bank) =>
      val newBalance = state.balance + amount
      // check here for withDrawl
      if (newBalance < 0) //illegal
        Effect.reply(bank)(BankAccountBalanceUpdatedResponse(None))
      else
        Effect
          .persist(BalanceUpdated(amount))
          .thenReply(bank)(newState => BankAccountBalanceUpdatedResponse(Some(newState)))
    case GetBankAccount(_, bank) =>
      Effect.reply(bank)(GetBankAccountResponse(Some(state)))
  }
  val eventHandler: (BankAccount, Event) => BankAccount = (state, event) => event match {
    case BankAccountCreated(bankAccount) => bankAccount
    case BalanceUpdated(amount) => state.copy(balance = state.balance + amount)
  }

  def apply(id: String): Behavior[Command] =
    EventSourcedBehavior[Command, Event, BankAccount](
      persistenceId = PersistenceId.ofUniqueId(id),
      emptyState = BankAccount(id, "", "", 0.0), // unused
      commandHandler = commandHandler,
      eventHandler = eventHandler
    )
}
