package counterApp

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ ActorSystem, Behavior }
import counterApp.TransactionalCountUp.ExecCommands

import scala.concurrent.duration.DurationInt

object Main extends App {

  def apply(): Behavior[Any] = Behaviors.setup { ctx =>
    val refA  = ctx.spawn(CounterA(), "counter-a")
    val refB  = ctx.spawn(CounterB(), "counter-b")
    val refTx = ctx.spawn(TransactionalCountUp(refA, refB), "tx-controller")
    val aggregateRef = ctx.spawn(
      Aggregator[Any, TransactionalCountUp.AggregateReply](
        // TODO 初期化とコマンドの実行を分離したい
        execCommands = { replyTo =>
          println("TransactionalCountUp exec commands")
          refA ! CounterA.CountUp(1, replyTo)
          refB ! CounterB.CountUp(1, replyTo)
        },
        expectedReplies = 2,
        refTx,
        aggregateReplies = replies => TransactionalCountUp.aggregateReplies(replies),
        timeout = Some(2.seconds)
      ),
      "aggregator"
    )

    refTx ! ExecCommands(aggregateRef)
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
