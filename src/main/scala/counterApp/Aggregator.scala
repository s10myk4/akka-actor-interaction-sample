package counterApp

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ ActorRef, Behavior }

import scala.concurrent.duration.FiniteDuration
import scala.reflect.ClassTag

object Aggregator {

  sealed trait Command
  case object ExecCommands extends Command
  case class Exec[T](f: ActorRef[T] => Unit)

  private case object ReceiveTimeout extends Command
  private case class WrappedReply[T](reply: T) extends Command

  def apply[Reply: ClassTag, Aggregate](
      execCommands: ActorRef[Reply] => Unit,
      expectedReplies: Int,
      replyTo: ActorRef[Aggregate],
      aggregateReplies: Seq[Reply] => Aggregate,
      timeout: Option[FiniteDuration]
  ): Behavior[Command] = Behaviors.setup { ctx =>
    timeout.foreach(t => ctx.setReceiveTimeout(t, ReceiveTimeout))

    def collect(replies: Seq[Reply]): Behavior[Command] = {
      Behaviors.receiveMessage {
        case ExecCommands =>
          ctx.log.debug("Aggregator receive ExecCommands")
          ctx.messageAdapter[Reply](WrappedReply(_))
          execCommands(ctx.messageAdapter[Reply](WrappedReply(_)))
          Behaviors.same
        case WrappedReply(reply) =>
          ctx.log.debug(s"Aggregator receive WrappedReply ${reply.asInstanceOf[Reply]}")
          val newReplies = replies :+ reply.asInstanceOf[Reply]
          if (newReplies.size == expectedReplies) {
            val result = aggregateReplies(newReplies)
            replyTo ! result
            Behaviors.stopped
          } else
            collect(newReplies)

        case ReceiveTimeout =>
          val aggregate = aggregateReplies(replies)
          replyTo ! aggregate
          Behaviors.stopped
      }
    }
    collect(List.empty)
  }

}
