package counterApp

import akka.actor.typed.scaladsl.AskPattern.Askable
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ ActorSystem, Behavior, Scheduler }
import akka.util.Timeout
import counterApp.CounterA.CounterACommand
import counterApp.CounterB.CounterBCommand
import counterApp.coordinater.BiCountUpCoordinateActorProtocol.ExecCommands
import counterApp.coordinater.{ BiCountUpCoordinateActorOfAkkaPersistence, BiCountUpCoordinateActorProtocol }

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.DurationInt
import scala.util.{ Failure, Success }

object Main extends App {

  def apply(): Behavior[Any] = Behaviors.setup { ctx =>
    val supervisorActorOfCounterA = ctx.spawn(
      SupervisorActor[CounterACommand](
        childActorName = "counter-a",
        childActorBehavior = CounterA()
      ),
      "counter-a-supervisor"
    )
    val supervisorActorOfCounterB = ctx.spawn(
      SupervisorActor[CounterBCommand](
        childActorName = "counter-b",
        childActorBehavior = CounterB()
      ),
      "counter-b-supervisor"
    )
    val refTx =
      ctx.spawn(
        BiCountUpCoordinateActorOfAkkaPersistence(supervisorActorOfCounterA, supervisorActorOfCounterB),
        // BiCountUpCoordinateActor.execCommand(supervisorActorOfCounterA, supervisorActorOfCounterB),
        "bi-count-up-coordinator"
      )

    implicit val timeout: Timeout     = Timeout(10.seconds)
    implicit val s: Scheduler         = ctx.system.scheduler
    implicit val ec: ExecutionContext = ctx.system.executionContext

    for (line <- io.Source.stdin.getLines()) {
      val num = line.toInt
      require(num > 0)
      refTx
        .ask[BiCountUpCoordinateActorProtocol.ExternalReply](replyTo => ExecCommands(num, replyTo))
        .onComplete {
          case Success(_: BiCountUpCoordinateActorProtocol.Success.type) =>
            println(s"Success / counterA: ${CounterA.value} / counterB: ${CounterB.value}")
          case Success(_: BiCountUpCoordinateActorProtocol.Failed.type) =>
            println("Failed")
          case Failure(ex) =>
            println("Failed", ex.getMessage)
        }
    }
    Behaviors.same
  }

  val system = ActorSystem[Any](
    apply(),
    "counter-app"
  )

  println("-- Please enter a positive integer. --")

}
