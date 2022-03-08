package counterApp

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ ActorRef, Behavior }

object SupervisorActor {

  def apply[CMD](
      childActorName: String,
      childActorBehavior: Behavior[CMD]
  ): Behavior[CMD] = {
    Behaviors.setup { ctx =>
      def getOrCreateChild: ActorRef[CMD] = {
        ctx
          .child(childActorName).fold {
            ctx.log.debug(s"Spawn a child actor.(name=$childActorName")
            ctx.spawn(childActorBehavior, childActorName)
          } { childRef =>
            ctx.log.debug(s"Get ref to child actor.(name=$childActorName")
            childRef.asInstanceOf[ActorRef[CMD]]
          }
      }

      Behaviors.receiveMessagePartial[CMD] { cmd =>
        getOrCreateChild ! cmd
        Behaviors.same
      }
    }
  }
}
