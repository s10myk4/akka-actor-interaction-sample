package counterApp

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ ActorRef, Behavior }

object CounterA {
  sealed trait CounterACommand

  final case class CountUp(num: Int, replyTo: ActorRef[CounterAReply]) extends CounterACommand

  final case class CountDown(num: Int, replyTo: ActorRef[CounterAReply]) extends CounterACommand

  sealed trait CounterAReply

  final case class SuccessfulCountUp(num: Int) extends CounterAReply
  final case class SuccessfulCountDown(num: Int) extends CounterAReply

  case object FailedCountUp extends CounterAReply
  case object FailedCountDown extends CounterAReply

  private var value = 0

  def apply(): Behavior[CounterACommand] = {
    Behaviors.receiveMessagePartial {
      case CountUp(num, replyTo) =>
        println("CounterA receive CountUp Command", replyTo)
        value = value + num
        val ran = scala.util.Random.nextInt()
        if (ran % 2 == 0) replyTo ! SuccessfulCountUp(value)
        else replyTo ! FailedCountUp
        Behaviors.same
      case CountDown(num, replyTo) =>
        value = value - num
        replyTo ! SuccessfulCountDown(value)
        Behaviors.same
    }
  }
}

object CounterB {
  sealed trait CounterBCommand

  final case class CountUp(num: Int, replyTo: ActorRef[CounterBReply]) extends CounterBCommand

  final case class CountDown(num: Int, replyTo: ActorRef[CounterBReply]) extends CounterBCommand

  sealed trait CounterBReply

  final case class SuccessfulCountUp(num: Int) extends CounterBReply
  final case class SuccessfulCountDown(num: Int) extends CounterBReply

  case object FailedCountUp extends CounterBReply
  case object FailedCountDown extends CounterBReply

  private var value = 0

  def apply(): Behavior[CounterBCommand] = {
    Behaviors.receiveMessagePartial {
      case CountUp(num, replyTo) =>
        println("CounterB receive CountUp Command", replyTo)
        value = value + num
        replyTo ! SuccessfulCountUp(value)
        Behaviors.same
      case CountDown(num, replyTo) =>
        value = value - num
        replyTo ! SuccessfulCountDown(value)
        Behaviors.same
    }
  }
}
