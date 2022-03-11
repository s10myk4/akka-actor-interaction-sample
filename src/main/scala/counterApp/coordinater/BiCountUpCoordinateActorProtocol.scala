package counterApp.coordinater

import akka.actor.typed.ActorRef

object BiCountUpCoordinateActorProtocol {
  sealed trait Command

  final case class ExecCommands(num: Int, replyTo: ActorRef[ExternalReply]) extends Command

  final case class AggregateReply(replies: Seq[InternalReply]) extends Command

  sealed trait Reply

  sealed trait ExternalReply extends Reply

  case object Success extends ExternalReply
  case object Failed extends ExternalReply

  sealed trait InternalReply extends Reply

  sealed trait SuccessfulReply extends InternalReply
  private[coordinater] final case class SuccessfulCounterAReply(num: Int) extends SuccessfulReply
  private[coordinater] final case class SuccessfulCounterBReply(num: Int) extends SuccessfulReply

  sealed trait FailedReply extends InternalReply
  private[coordinater] case object FailedCounterAReply extends FailedReply
  private[coordinater] case object FailedCounterBReply extends FailedReply
}
