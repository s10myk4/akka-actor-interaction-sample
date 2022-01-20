package counterApp

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ ActorSystem, Behavior }
import counterApp.TransactionalCountUp.ExecCommands

object Main extends App {

  def apply(): Behavior[Any] = Behaviors.setup { ctx =>
    val refA  = ctx.spawn(CounterA(), "counter-a")
    val refB  = ctx.spawn(CounterB(), "counter-b")
    val refTx = ctx.spawn(TransactionalCountUp(refA, refB), "tx-controller")

    refTx ! ExecCommands(1)
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
