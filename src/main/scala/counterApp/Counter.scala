package counterApp

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ ActorRef, Behavior }
import org.slf4j.LoggerFactory

object CounterA {

  private val logger = LoggerFactory.getLogger(getClass.getName)

  sealed trait CounterACommand

  final case class CountUp(num: Int, replyTo: ActorRef[CounterAReply]) extends CounterACommand

  sealed trait CounterAReply

  final case class SuccessfulCountUp(num: Int, total: Int) extends CounterAReply

  case object FailedCountUp extends CounterAReply

  var value = 0

  def apply(): Behavior[CounterACommand] = {
    Behaviors.receiveMessage { case CountUp(num, replyTo) =>
      logger.debug("CounterA receive CountUp Command", replyTo)
      val ran = RandomNum.generate()
      logger.debug(s"ran ${ran.value}")
      if (ran.isMultipleOf5) () // noReply
      else if (ran.isEven) {
        value = value + num
        replyTo ! SuccessfulCountUp(num, value)
      } else replyTo ! FailedCountUp
      Behaviors.same
    }
  }
}

object CounterB {

  private val logger = LoggerFactory.getLogger(getClass.getName)

  sealed trait CounterBCommand

  final case class CountUp(num: Int, replyTo: ActorRef[CounterBReply]) extends CounterBCommand

  sealed trait CounterBReply

  final case class SuccessfulCountUp(num: Int, total: Int) extends CounterBReply

  case object FailedCountUp extends CounterBReply

  var value = 0

  def apply(): Behavior[CounterBCommand] = {
    Behaviors.receiveMessage { case CountUp(num, replyTo) =>
      logger.debug("CounterB receive CountUp Command", replyTo)
      val ran = RandomNum.generate()
      logger.debug(s"ran ${ran.value}")
      if (ran.isMultipleOf5) () // noReply
      else if (ran.isEven) {
        value = value + num
        replyTo ! SuccessfulCountUp(num, value)
      } else replyTo ! FailedCountUp
      Behaviors.same
    }
  }
}

object RandomNum {
  final case class RandomNum(value: Int) {
    def isEven: Boolean        = value % 2 == 0
    def isOdd: Boolean         = value % 2 != 0
    def isMultipleOf5: Boolean = value % 5 == 0
  }

  def generate(): RandomNum = RandomNum(scala.util.Random.nextInt())
}
