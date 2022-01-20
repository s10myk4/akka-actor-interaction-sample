package counterApp

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ ActorRef, Behavior }
import counterApp.CounterA.CounterACommand
import counterApp.CounterB.CounterBCommand

object TransactionalCountUp {

  sealed trait Command

  sealed trait Reply

  final case class ExecCommands(aggregatorRef: ActorRef[Aggregator.ExecCommands.type]) extends Command

  final case class AggregateReply(replies: List[Reply]) extends Command

  case class SuccessfulReply(num: Int) extends Reply
  case object FailedReply extends Reply

  def apply(counterA: ActorRef[CounterACommand], counterB: ActorRef[CounterBCommand]): Behavior[Command] = {
    Behaviors.setup { _ =>
      Behaviors.receiveMessage {
        case cmd: ExecCommands =>
          println("@@ TransactionalCountUp receiveMessage ExecCommands")
          cmd.aggregatorRef ! Aggregator.ExecCommands
          Behaviors.same
        case AggregateReply(replies) if !replies.contains(FailedReply) =>
          println("All commands were successful.", replies)
          Behaviors.same
        case AggregateReply(replies) =>
          // TODO 打ち消しコマンドを発行できるようにする
          println("All commands did not succeed.", replies)
          Behaviors.same
      }
    }
  }

  def aggregateReplies(replies: List[Any]): TransactionalCountUp.AggregateReply = {
    println("aggregateReplies")
    AggregateReply(
      replies.map {
        case CounterA.SuccessfulCountUp(num) => SuccessfulReply(num)
        case CounterA.FailedCountUp          => FailedReply
        case CounterB.SuccessfulCountUp(num) => SuccessfulReply(num)
        case CounterB.FailedCountUp          => FailedReply
        case unknown                         => throw new RuntimeException(s"Unknown reply $unknown")
      }
    )
  }

}
