package counterApp.coordinater

import akka.actor.typed.scaladsl.{ ActorContext, Behaviors }
import akka.actor.typed.{ ActorRef, Behavior }
import counterApp.CounterA.CounterACommand
import counterApp.CounterB.CounterBCommand
import counterApp.{ Aggregator, CounterA, CounterB }

import scala.concurrent.duration.DurationInt

object BiCountUpCoordinateActor {

  import counterApp.coordinater.BiCountUpCoordinateActorProtocol._
  private val expectedReplyNum = 2

  final case class RetryCount(private val value: Int, private val limitNum: Int) {
    def increment: RetryCount = copy(value = this.value + 1)
    def canRetry: Boolean     = value <= limitNum
  }
  object RetryCount {
    def init(limitNum: Int): RetryCount = RetryCount(1, limitNum)
  }

  def execCommand(counterA: ActorRef[CounterACommand], counterB: ActorRef[CounterBCommand]): Behavior[Command] =
    Behaviors.setup { ctx =>
      Behaviors.receiveMessage {
        case cmd: ExecCommands =>
          val aggregatorRef = spawnAggregator(
            ctx,
            // TODO ExecCommands経由でコマンドを渡せるようにしたいけど
            replyTo => {
              counterA ! CounterA.CountUp(cmd.num, replyTo)
              counterB ! CounterB.CountUp(cmd.num, replyTo)
            },
            expectedReplies = expectedReplyNum
          )
          aggregatorRef ! Aggregator.ExecCommands
          handleReplies(counterA, counterB, cmd)
        case _ =>
          Behaviors.unhandled
      }
    }

  private def handleReplies(
      counterA: ActorRef[CounterACommand],
      counterB: ActorRef[CounterBCommand],
      cmd: ExecCommands
  ): Behavior[Command] = Behaviors.setup { ctx =>
    Behaviors.receiveMessage {
      case AggregateReply(replies)
          if replies.forall(_.isInstanceOf[SuccessfulReply]) && replies.length == expectedReplyNum =>
        println("All commands were successful.", replies)
        cmd.replyTo ! Success
        execCommand(counterA, counterB)
      case AggregateReply(replies) if replies.forall(_.isInstanceOf[FailedReply]) =>
        println("全てのコマンドが失敗", replies)
        cmd.replyTo ! Failed
        execCommand(counterA, counterB)
      case AggregateReply(replies)
          if replies.exists(_.isInstanceOf[SuccessfulCounterAReply]) && (replies.length < expectedReplyNum || replies
            .contains(FailedCounterBReply)) =>
        println("CounterAへのコマンドだけが成功", replies)
        val aggregatorRef = spawnAggregator(
          ctx,
          replyTo => counterB ! CounterB.CountUp(cmd.num, replyTo),
          expectedReplies = 1
        )
        println("CounterBへコマンドをリトライ")
        aggregatorRef ! Aggregator.ExecCommands
        retrying(counterA, counterB, Right(counterB), cmd.num, cmd.replyTo, RetryCount.init(retryLimitNum))
      case AggregateReply(replies)
          if replies.exists(_.isInstanceOf[SuccessfulCounterBReply]) && (replies.length < expectedReplyNum || replies
            .contains(FailedCounterAReply)) =>
        println("CounterBへのコマンドだけが成功.", replies)
        val aggregatorRef = spawnAggregator(
          ctx,
          replyTo => counterA ! CounterA.CountUp(cmd.num, replyTo),
          expectedReplies = 1
        )
        println("CounterAへコマンドをリトライ")
        aggregatorRef ! Aggregator.ExecCommands

        retrying(counterA, counterB, Left(counterA), cmd.num, cmd.replyTo, RetryCount.init(retryLimitNum))
      case _ =>
        Behaviors.unhandled
    }
  }

  private val retryLimitNum = 3

  // Stateオブジェクトからこのパラメータを関数で取得できるようにする
  // 最終的にその関数がpersistence actorから状態を取得してくる
  private def retrying(
      counterA: ActorRef[CounterACommand],
      counterB: ActorRef[CounterBCommand],
      retryTargets: Either[ActorRef[CounterACommand], ActorRef[CounterBCommand]],
      num: Int,
      replyTo: ActorRef[ExternalReply],
      retryCount: RetryCount
  ): Behavior[Command] = Behaviors.setup { ctx =>
    Behaviors.receiveMessagePartial {
      case AggregateReply(xs) if xs.nonEmpty && xs.forall(_.isInstanceOf[SuccessfulReply]) =>
        println("リトライ成功")
        replyTo ! Success
        execCommand(counterA, counterB)

      case AggregateReply(xs) if xs.isEmpty =>
        println("タイムアウトで再度リトライ")
        // if (retryCount.canRetry) {
        val (aggregatorRef, retryTarget) = retryTargets match {
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
        retrying(counterA, counterB, retryTarget, num, replyTo, retryCount.increment)
      // } else {
      //  replyTo ! Failed
      //  Behaviors.same // TODO
      // }

      case AggregateReply(head :: tail) if head.isInstanceOf[FailedReply] && tail.isEmpty =>
        println("リトライ失敗で再度リトライ")
        // if (retryCount.canRetry) {
        val (aggregatorRef, retryTarget) = retryTargets match {
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
        retrying(counterA, counterB, retryTarget, num, replyTo, retryCount.increment)
      // } else {
      //  replyTo ! Failed
      //  Behaviors.same // TODO
      // }
    }
  }

  private def spawnAggregator(
      ctx: ActorContext[Command],
      execCommands: ActorRef[Any] => Unit,
      expectedReplies: Int
  ): ActorRef[Aggregator.Command] =
    ctx.spawnAnonymous(
      Aggregator[Any, AggregateReply](
        execCommands,
        expectedReplies,
        ctx.self,
        replies => mapInternalReplies(replies),
        timeout = Some(2.seconds)
      )
    )

  private def mapInternalReplies(replies: Seq[Any]): AggregateReply = {
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
