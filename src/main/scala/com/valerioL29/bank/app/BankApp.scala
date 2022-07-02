package com.valerioL29.bank.app

import akka.actor.typed._
import akka.actor.typed.scaladsl.AskPattern._
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Route
import akka.util.Timeout
import com.valerioL29.bank.actors.Bank
import com.valerioL29.bank.actors.PersistentBankAccount.Command
import com.valerioL29.bank.http.BankRoutes

import java.net.InetSocketAddress
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util._

object BankApp {
  def startHttpServer(bank: ActorRef[Command])(implicit system: ActorSystem[_]): Unit = {
    implicit val ec: ExecutionContext = system.executionContext
    val router = new BankRoutes(bank)
    val routes: Route = router.routes

    val httpBindingFuture: Future[Http.ServerBinding] = Http().newServerAt("localhost", 8080).bind(routes)
    httpBindingFuture.onComplete {
      case Success(binding) =>
        val address: InetSocketAddress = binding.localAddress
        system.log.info(s"Server online at http://${address.getHostString}:${address.getPort}")
      case Failure(ex) =>
        system.log.error(s"Failed to bind HTTP server, because $ex")
        system.terminate()
    }
  }

  def main(args: Array[String]): Unit = {
    trait RootCommand
    case class RetrieveBankActor(replyTo: ActorRef[ActorRef[Command]]) extends RootCommand

    val rootBehavior: Behavior[RootCommand] = Behaviors.setup { context: ActorContext[RootCommand] =>
      val bankActor: ActorRef[Command] = context.spawn(Bank(), "bank")

      Behaviors.receiveMessage {
        case RetrieveBankActor(replyTo) =>
          replyTo ! bankActor
          Behaviors.same
      }
    }

    implicit val system: ActorSystem[RootCommand] = ActorSystem(rootBehavior, "BankSystem")
    implicit val timeout: Timeout = Timeout(5.seconds)
    implicit val ec: ExecutionContext = system.executionContext

    val bankActorFuture: Future[ActorRef[Command]] = system.ask((replyTo: ActorRef[ActorRef[Command]]) => RetrieveBankActor(replyTo))
    bankActorFuture.foreach(startHttpServer)
  }
}

//>>> import requests
//>>> payload={"user":"anna","currency":"EUR","balance":1000}
//>>> url = 'http://localhost:8080/bank'
//>>> r = requests.post(url, json=payload)
//>>> r.text
//'The request has been fulfilled and resulted in a new resource being created.'
