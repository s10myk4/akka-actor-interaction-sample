package counterApp

import akka.actor.typed.scaladsl.{ ActorContext, Behaviors }
import akka.actor.typed.{ ActorRef, Behavior }
import counterApp.CounterA.CounterACommand
import counterApp.CounterB.CounterBCommand

import scala.concurrent.duration.DurationInt

object TransactionalCountUp {

  sealed trait Command

  sealed trait Reply

  final case class ExecCommands(num: Int) extends Command

  final case class AggregateReply(replies: List[Reply]) extends Command

  sealed trait SuccessfulReply extends Reply
  final case class SuccessfulCounterAReply(num: Int) extends SuccessfulReply
  final case class SuccessfulCounterBReply(num: Int) extends SuccessfulReply

  sealed trait FailedReply extends Reply
  case object FailedCounterAReply extends FailedReply
  case object FailedCounterBReply extends FailedReply

  def apply(counterA: ActorRef[CounterACommand], counterB: ActorRef[CounterBCommand]): Behavior[Command] = {
    Behaviors.setup { ctx =>
      Behaviors.receiveMessage {
        case ExecCommands(num) =>
          println("TransactionalCountUp receiveMessage ExecCommands")
          val aggregatorRef = spawnAggregator(
            ctx,
            // TODO ExecCommands経由でコマンドを渡せるようにしたいけどreplyToの関係でむずい
            replyTo => {
              counterA ! CounterA.CountUp(num, replyTo)
              counterB ! CounterB.CountUp(num, replyTo)
            },
            expectedReplies = 2
          )
          aggregatorRef ! Aggregator.ExecCommands
          Behaviors.same
        // TODO timeoutするとreplyが1つしかこないので、その前提で条件を書く必要あり
        case AggregateReply(replies) if replies.forall(_.isInstanceOf[SuccessfulCounterAReply]) =>
          println("All commands were successful.", replies)
          Behaviors.same
        case AggregateReply(replies) if replies.forall(_.isInstanceOf[FailedReply]) =>
          println("全てのコマンドが失敗", replies)
          Behaviors.same
        case AggregateReply(replies) if replies.contains(FailedCounterAReply) =>
          println("CounterAのコマンドが失敗.", replies)
          val aggregatorRef = spawnAggregator(
            ctx,
            replyTo => counterB ! CounterB.CountDown(1, replyTo),
            expectedReplies = 1
          )
          println("CounterBへ打ち消しコマンドを実行", replies)
          aggregatorRef ! Aggregator.ExecCommands
          Behaviors.same
        case AggregateReply(replies) if replies.contains(FailedCounterBReply) =>
          println("CounterBのコマンドが失敗", replies)
          val aggregatorRef = spawnAggregator(
            ctx,
            replyTo => counterA ! CounterA.CountDown(1, replyTo),
            expectedReplies = 1
          )
          println("CounterAへ打ち消しコマンドを実行", replies)
          aggregatorRef ! Aggregator.ExecCommands
          Behaviors.same
        // TODO 打ち消しコマンドが失敗した場合の考慮も必要そう
      }
    }
  }

  private def spawnAggregator(
      ctx: ActorContext[Command],
      execCommands: ActorRef[Any] => Unit,
      expectedReplies: Int
  ): ActorRef[Aggregator.Command] =
    ctx.spawnAnonymous(
      Aggregator[Any, TransactionalCountUp.AggregateReply](
        execCommands,
        expectedReplies,
        ctx.self,
        replies => TransactionalCountUp.aggregateReplies(replies),
        timeout = Some(2.seconds) // TODO
      )
    )

  private def aggregateReplies(replies: List[Any]): TransactionalCountUp.AggregateReply = {
    AggregateReply(
      replies.map {
        case CounterA.SuccessfulCountUp(num, _) => SuccessfulCounterAReply(num)
        case CounterA.FailedCountUp             => FailedCounterAReply
        case CounterB.SuccessfulCountUp(num, _) => SuccessfulCounterBReply(num)
        case CounterB.FailedCountUp             => FailedCounterBReply
        case unknown                            => throw new RuntimeException(s"Unknown reply $unknown")
      }
    )
  }

}
