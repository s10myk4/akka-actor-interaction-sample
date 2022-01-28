package counterApp

import akka.actor.typed.scaladsl.AskPattern.Askable
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ ActorSystem, Behavior, Scheduler }
import akka.util.Timeout
import counterApp.TransactionalCountUp.ExecCommands

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.DurationInt
import scala.util.{ Failure, Success }

object Main extends App {

  def apply(): Behavior[Any] = Behaviors.setup { ctx =>
    implicit val timeout: Timeout     = Timeout(10.seconds)
    implicit val s: Scheduler         = ctx.system.scheduler
    implicit val ec: ExecutionContext = ctx.system.executionContext

    val refA  = ctx.spawn(CounterA(), "counter-a")
    val refB  = ctx.spawn(CounterB(), "counter-b")
    val refTx = ctx.spawn(TransactionalCountUp(refA, refB), "tx-controller")

    refTx
      .ask[TransactionalCountUp.ExternalReply](replyTo => ExecCommands(1, replyTo))
      .onComplete {
        case Success(_: TransactionalCountUp.Success.type) =>
          println("Success")
        case Success(_: TransactionalCountUp.Failed.type) =>
          println("Failed")
        case Failure(ex) =>
          println("Failed", ex.getMessage)
      }
    Behaviors.same
  }

  val system = ActorSystem[Any](
    apply(),
    "counter-app"
  )

  // for (line <- io.Source.stdin.getLines()) {
  //  val num = line.toInt
  //  println(num)
  // }

}
