package counterApp.coordinater

import akka.actor.typed.scaladsl.{ ActorContext, Behaviors }
import akka.actor.typed.{ ActorRef, Behavior }
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{ Effect, EventSourcedBehavior, ReplyEffect }
import counterApp.CounterA.CounterACommand
import counterApp.CounterB.CounterBCommand
import counterApp.coordinater.BiCountUpCoordinateActorProtocol._
import counterApp.{ Aggregator, CounterA, CounterB }

import scala.concurrent.duration.DurationInt

object BiCountUpCoordinateActorOfAkkaPersistence {

  sealed trait Event
  final case class Executed(cmd: ExecCommands) extends Event
  final case class FailedCounterACommand(cmd: ExecCommands) extends Event
  final case class FailedCounterBCommand(cmd: ExecCommands) extends Event
  final case class Succeeded() extends Event

  sealed trait State
  case object InitialState extends State
  final case class CommandExecuting(cmd: ExecCommands) extends State
  final case class RetryingCounterACommand(cmd: ExecCommands) extends State
  final case class RetryingCounterBCommand(cmd: ExecCommands) extends State

  def apply(
      counterARef: ActorRef[CounterACommand],
      counterBRef: ActorRef[CounterBCommand]
  ): Behavior[Command] =
    Behaviors.setup { ctx: ActorContext[Command] =>
      EventSourcedBehavior.withEnforcedReplies[Command, Event, State](
        PersistenceId.ofUniqueId("bi-count-up-coordinator"),
        emptyState = InitialState,
        commandHandler(_, _)(ctx, counterARef, counterBRef),
        eventHandler
      )
    }

  private val expectedReplyNum           = 2
  private val expectedReplyNumOnRetrying = 1

  private def commandHandler(state: State, cmd: Command)(
      ctx: ActorContext[Command],
      counterARef: ActorRef[CounterACommand],
      counterBRef: ActorRef[CounterBCommand]
  ): ReplyEffect[Event, State] = {
    state match {
      case InitialState =>
        cmd match {
          case cmd: ExecCommands =>
            println("コマンドを実行", cmd)
            val aggregatorRef = spawnAggregator(
              ctx,
              replyTo => {
                counterARef ! CounterA.CountUp(cmd.num, replyTo)
                counterBRef ! CounterB.CountUp(cmd.num, replyTo)
              },
              expectedReplies = expectedReplyNum
            )
            Effect.persist(Executed(cmd)).thenReply(aggregatorRef)(_ => Aggregator.ExecCommands)

          case _ =>
            Effect.unhandled.thenNoReply()
        }
      case state: CommandExecuting =>
        cmd match {
          case aggregateReply: AggregateReply if aggregateReply.isAllSuccessful(expectedReplyNum) =>
            println("すべてのコマンドが成功", aggregateReply.replies)
            // TODO この場合は永続化する必要ある?
            Effect.persist(Succeeded()).thenReply(state.cmd.replyTo)(_ => Success)
          // Effect.none.thenReply(state.cmd.replyTo)(_ => Success)

          case aggregateReply: AggregateReply if aggregateReply.isAllFailed =>
            println("すべてのコマンドが失敗", aggregateReply.replies)
            // TODO この場合は永続化する必要ある?
            Effect.none.thenReply(state.cmd.replyTo)(_ => Failed)

          case AggregateReply(replies)
              if replies.exists(
                _.isInstanceOf[SuccessfulCounterAReply]
              ) && (replies.length < expectedReplyNum || replies
                .contains(FailedCounterBReply)) =>
            println("CounterAへのコマンドだけが成功", replies)
            val aggregatorRef = spawnAggregator(
              ctx,
              replyTo => counterBRef ! CounterB.CountUp(state.cmd.num, replyTo),
              expectedReplies = expectedReplyNumOnRetrying
            )
            Effect
              .persist(FailedCounterBCommand(state.cmd))
              .thenReply(aggregatorRef)(_ => Aggregator.ExecCommands)

          case AggregateReply(replies)
              if replies.exists(
                _.isInstanceOf[SuccessfulCounterBReply]
              ) && (replies.length < expectedReplyNum || replies
                .contains(FailedCounterAReply)) =>
            println("CounterBへのコマンドだけが成功", replies)
            val aggregatorRef = spawnAggregator(
              ctx,
              replyTo => counterARef ! CounterA.CountUp(state.cmd.num, replyTo),
              expectedReplies = expectedReplyNumOnRetrying
            )
            Effect
              .persist(FailedCounterACommand(state.cmd))
              .thenReply(aggregatorRef)(_ => Aggregator.ExecCommands)
          case _ =>
            Effect.unhandled.thenNoReply
        }

      case state: RetryingCounterACommand =>
        cmd match {
          case aggregateReply: AggregateReply if aggregateReply.isAllSuccessful(expectedReplyNumOnRetrying) =>
            println("CounterAへのリトライ成功", aggregateReply.replies)
            Effect.persist(Succeeded()).thenReply(state.cmd.replyTo)(_ => Success)

          case aggregateReply: AggregateReply if aggregateReply.isOneFailed =>
            println("CounterAへのリトライ失敗で再度リトライ")
            retryToCounterA(ctx, counterARef, state)

          case aggregateReply: AggregateReply if aggregateReply.isTimeoutAll =>
            println("CounterAへのリトライがタイムアウトで再度リトライ", aggregateReply.replies)
            retryToCounterA(ctx, counterARef, state)
          case _ =>
            Effect.unhandled.thenNoReply
        }

      case state: RetryingCounterBCommand =>
        cmd match {
          case aggregateReply: AggregateReply if aggregateReply.isAllSuccessful(expectedReplyNumOnRetrying) =>
            println("CounterBへのリトライ成功", aggregateReply.replies)
            Effect.persist(Succeeded()).thenReply(state.cmd.replyTo)(_ => Success)

          case aggregateReply: AggregateReply if aggregateReply.isOneFailed =>
            println("CounterBへのリトライ失敗で再度リトライ")
            retryToCounterB(ctx, counterBRef, state)

          case aggregateReply: AggregateReply if aggregateReply.isTimeoutAll =>
            println("CounterBへのリトライがタイムアウトで再度リトライ", aggregateReply.replies)
            retryToCounterB(ctx, counterBRef, state)

          case _ =>
            Effect.unhandled.thenNoReply
        }
    }
  }

  private def retryToCounterA(
      ctx: ActorContext[Command],
      counterARef: ActorRef[CounterACommand],
      state: RetryingCounterACommand
  ): ReplyEffect[Event, State] = {
    val aggregatorRef = spawnAggregator(
      ctx,
      replyTo => counterARef ! CounterA.CountUp(state.cmd.num, replyTo),
      expectedReplies = expectedReplyNumOnRetrying
    )
    Effect.none.thenReply(aggregatorRef)(_ => Aggregator.ExecCommands)
  }

  private def retryToCounterB(
      ctx: ActorContext[Command],
      counterBRef: ActorRef[CounterBCommand],
      state: RetryingCounterBCommand
  ): ReplyEffect[Event, State] = {
    val aggregatorRef = spawnAggregator(
      ctx,
      replyTo => counterBRef ! CounterB.CountUp(state.cmd.num, replyTo),
      expectedReplies = expectedReplyNumOnRetrying
    )
    Effect.none.thenReply(aggregatorRef)(_ => Aggregator.ExecCommands)
  }

  private val eventHandler: (State, Event) => State = (state, event) => {
    println(s"eventHandler with State: $state Event: $event")
    (state, event) match {
      case (InitialState, event: Executed) =>
        CommandExecuting(event.cmd)
      case (_: CommandExecuting, _: Succeeded) =>
        InitialState
      case (_: CommandExecuting, event: FailedCounterACommand) =>
        RetryingCounterACommand(event.cmd)
      case (_: CommandExecuting, event: FailedCounterBCommand) =>
        RetryingCounterBCommand(event.cmd)
      case (_: RetryingCounterACommand, _: Succeeded) =>
        InitialState
      case (_: RetryingCounterBCommand, _: Succeeded) =>
        InitialState
      case (state, _) =>
        state
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
