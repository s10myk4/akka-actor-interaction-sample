package counterApp

import akka.actor.typed.scaladsl.{ ActorContext, Behaviors }
import akka.actor.typed.{ ActorRef, Behavior }
import counterApp.CounterA.CounterACommand
import counterApp.CounterB.CounterBCommand

import scala.concurrent.duration.DurationInt

object TransactionalCountUp {

  sealed trait Command

  final case class ExecCommands(num: Int, replyTo: ActorRef[ExternalReply]) extends Command

  final case class AggregateReply(replies: Seq[InternalReply]) extends Command

  sealed trait Reply

  sealed trait ExternalReply extends Reply

  case object Success extends ExternalReply
  case object Failed extends ExternalReply

  sealed trait InternalReply extends Reply

  sealed trait SuccessfulReply extends InternalReply
  private final case class SuccessfulCounterAReply(num: Int) extends SuccessfulReply
  private final case class SuccessfulCounterBReply(num: Int) extends SuccessfulReply

  sealed trait FailedReply extends InternalReply
  private case object FailedCounterAReply extends FailedReply
  private case object FailedCounterBReply extends FailedReply

  private val expectedReplyNum = 2

  def apply(counterA: ActorRef[CounterACommand], counterB: ActorRef[CounterBCommand]): Behavior[Command] =
    Behaviors.setup { ctx =>
      Behaviors.receiveMessagePartial { case cmd: ExecCommands =>
        println("TransactionalCountUp receiveMessage ExecCommands")
        val aggregatorRef = spawnAggregator(
          ctx,
          // TODO ExecCommands経由でコマンドを渡せるようにしたいけどreplyToの関係でむずい
          replyTo => {
            counterA ! CounterA.CountUp(cmd.num, replyTo)
            counterB ! CounterB.CountUp(cmd.num, replyTo)
          },
          expectedReplies = expectedReplyNum
        )
        aggregatorRef ! Aggregator.ExecCommands
        handleReplies(counterA, counterB, cmd)
      }
    }

  private def handleReplies(
      counterA: ActorRef[CounterACommand],
      counterB: ActorRef[CounterBCommand],
      cmd: ExecCommands
  ): Behavior[Command] = Behaviors.setup { ctx =>
    Behaviors.receiveMessagePartial {
      case AggregateReply(replies)
          if replies.forall(_.isInstanceOf[SuccessfulReply]) && replies.length == expectedReplyNum =>
        println("All commands were successful.", replies)
        cmd.replyTo ! Success
        Behaviors.same
      case AggregateReply(replies) if replies.forall(_.isInstanceOf[FailedReply]) =>
        println("全てのコマンドが失敗", replies)
        cmd.replyTo ! Failed
        Behaviors.same
      case AggregateReply(replies)
          if replies.exists(_.isInstanceOf[SuccessfulCounterAReply]) && (replies.length < expectedReplyNum || replies
            .contains(FailedCounterBReply)) =>
        println("CounterAへのコマンドだけが成功", replies)
        val aggregatorRef = spawnAggregator(
          ctx,
          replyTo => counterB ! CounterB.CountUp(cmd.num, replyTo),
          expectedReplies = 1
        )
        println("CounterBへコマンドをリトライ", replies)
        aggregatorRef ! Aggregator.ExecCommands
        retrying(counterA, counterB, cmd.replyTo)
      case AggregateReply(replies)
          if replies.exists(_.isInstanceOf[SuccessfulCounterBReply]) && (replies.length < expectedReplyNum || replies
            .contains(FailedCounterAReply)) =>
        println("CounterBへのコマンドだけが成功.", replies)
        val aggregatorRef = spawnAggregator(
          ctx,
          replyTo => counterA ! CounterA.CountUp(cmd.num, replyTo),
          expectedReplies = 1
        )
        println("CounterAへコマンドをリトライ", replies)
        aggregatorRef ! Aggregator.ExecCommands
        retrying(counterA, counterB, cmd.replyTo)
    }
  }

  val retryLimitNum = 3

  private def retrying(
      counterA: ActorRef[CounterACommand],
      counterB: ActorRef[CounterBCommand],
      replyTo: ActorRef[ExternalReply],
      retryCount: Int = 0
  ): Behavior[Command] = Behaviors.setup { ctx =>
    Behaviors.receiveMessagePartial {
      case AggregateReply(xs) if xs.forall(_.isInstanceOf[SuccessfulReply]) =>
        // 成功
        println("retry success")
        replyTo ! Success
        Behaviors.same

      case AggregateReply(xs) if xs.isEmpty =>
        // タイムアウト
        if (retryCount <= retryLimitNum) {
          // 再度リトライ
          // TODO 1個前に実行したコマンドを覚えてる必要あり
          retrying(counterA, counterB, replyTo, retryCount + 1)
        } else {
          replyTo ! Failed
          Behaviors.same
        }

      case AggregateReply(head :: tail) if head.isInstanceOf[FailedReply] && tail.isEmpty =>
        val aggregatorRef = spawnAggregator(
          ctx,
          replyTo => {
            if (head.isInstanceOf[FailedCounterAReply.type]) counterA ! CounterA.CountUp(1, replyTo)
            else counterB ! CounterB.CountUp(1, replyTo)
          },
          expectedReplies = 1
        )
        aggregatorRef ! Aggregator.ExecCommands
        retrying(counterA, counterB, replyTo, retryCount + 1)
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
        replies => mapInternalReplies(replies),
        timeout = Some(2.seconds)
      )
    )

  private def mapInternalReplies(replies: Seq[Any]): TransactionalCountUp.AggregateReply = {
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
