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

  final case class RetryCount(private val value: Int, private val limitNum: Int) {
    def increment: RetryCount = copy(value = this.value + 1)
    def canRetry: Boolean     = value <= limitNum
  }
  object RetryCount {
    def init(limitNum: Int): RetryCount = RetryCount(1, limitNum)
  }

  def apply(counterA: ActorRef[CounterACommand], counterB: ActorRef[CounterBCommand]): Behavior[Command] =
    Behaviors.setup { ctx =>
      Behaviors.receiveMessagePartial { case cmd: ExecCommands =>
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
        retrying(Right(counterB), cmd.num, cmd.replyTo, RetryCount.init(retryLimitNum))
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
        retrying(Left(counterA), cmd.num, cmd.replyTo, RetryCount.init(retryLimitNum))
    }
  }

  private val retryLimitNum = 3

  private def retrying(
      counterRef: Either[ActorRef[CounterACommand], ActorRef[CounterBCommand]],
      num: Int,
      replyTo: ActorRef[ExternalReply],
      retryCount: RetryCount
  ): Behavior[Command] = Behaviors.setup { ctx =>
    Behaviors.receiveMessagePartial {
      case AggregateReply(xs) if xs.forall(_.isInstanceOf[SuccessfulReply]) =>
        println("リトライ成功")
        replyTo ! Success
        Behaviors.same

      case AggregateReply(xs) if xs.isEmpty =>
        println("タイムアウトで再度リトライ")
        if (retryCount.canRetry) {
          val (aggregatorRef, counter) = counterRef match {
            case Left(counterA) =>
              (
                spawnAggregator(ctx, replyTo => counterA ! CounterA.CountUp(num, replyTo), expectedReplies = 1),
                Left(counterA)
              )
            case Right(counterB) =>
              (
                spawnAggregator(ctx, replyTo => counterB ! CounterB.CountUp(num, replyTo), expectedReplies = 1),
                Right(counterB)
              )
          }
          aggregatorRef ! Aggregator.ExecCommands
          retrying(counter, num, replyTo, retryCount.increment)
        } else {
          // TODO リトライ失敗した場合にその状態を保存する
          replyTo ! Failed
          Behaviors.same
        }

      // リトライして失敗
      case AggregateReply(head :: tail) if head.isInstanceOf[FailedReply] && tail.isEmpty =>
        println("リトライ失敗で再度リトライ")
        if (retryCount.canRetry) {
          val (aggregatorRef, counter) = counterRef match {
            case Left(counterA) =>
              (
                spawnAggregator(ctx, replyTo => counterA ! CounterA.CountUp(num, replyTo), expectedReplies = 1),
                Left(counterA)
              )
            case Right(counterB) =>
              (
                spawnAggregator(ctx, replyTo => counterB ! CounterB.CountUp(num, replyTo), expectedReplies = 1),
                Right(counterB)
              )
          }
          aggregatorRef ! Aggregator.ExecCommands
          retrying(counter, num, replyTo, retryCount.increment)
        } else {
          replyTo ! Failed
          Behaviors.same
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
