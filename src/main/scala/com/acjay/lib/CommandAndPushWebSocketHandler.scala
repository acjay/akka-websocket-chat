package com.acjay.lib

import akka.actor.ActorRef
import akka.http.scaladsl.model.ws.{BinaryMessage, Message, TextMessage}
import akka.stream.scaladsl.{Flow, Source, SourceQueue}
import akka.stream.{ActorMaterializer, KillSwitch, KillSwitches, OverflowStrategy}
import scala.concurrent.{ExecutionContextExecutor, Future}

trait CommandAndPushWebSocketHandler { self =>
  /** Supertype of incoming commands from the client. */
  type Cmd

  /** 
    * Supertype of results of incoming commands. 
    *
    * This will be wrapped in a Responding Action for futher processing.
    */
  type Res

  /** 
    * Supertype of results of incoming commands. 
    *
    * This will be wrapped in a Telling Action for futher processing.
    */
  type Push
  
  /** 
    * The supertype of the domain logic output.
    *
    * Primarily, this contain all data going back to the user, including error
    * conditions, but you may also use this to propagate any bookkeeping data 
    * accumulated during through the flow.
    */ 
  type Out

  /** Session data that should persist for the life of the connection. */
  type Sess

  /**
    * Represents all possible actions the web socket connection may need to 
    * perform.
    */
  sealed trait Action
  case object Starting extends Action
  case class Responding(commandResult: Res) extends Action
  case class Telling(pushMessage: Push) extends Action
  case object Ending extends Action

  implicit def ec: ExecutionContextExecutor

  val pushMessageSource: CommandAndPushWebSocketHandler.PushMessageSource[Push]

  case class ConnectionControl(
    pushMessageSource: self.pushMessageSource.type,
    shutdown: () => Unit,
    crash: Throwable => Unit
  )

  def getState(): Future[Sess]
  def setState(state: Sess): Future[Unit]
  def deserialize(command: Message): Future[Cmd]
  def processCommand(command: Cmd, state: Sess): Future[Res]
  def processAction(action: Action, state: Sess, connectionControl: ConnectionControl): Future[(Option[Out], Sess)]
  def serialize(output: Out): Future[Message]

  // Internal state. This is a constant, practically speaking, because it 
  // will be set when the Flow is materialized, and then never be mutated.
  private var killSwitch: KillSwitch = null

  final private lazy val connectionControl = ConnectionControl(
    pushMessageSource = self.pushMessageSource,
    shutdown = () => killSwitch.shutdown(),
    crash = (ex: Throwable) => killSwitch.abort(ex)
  )

  final private def withState[A](a: A): Future[(A, Sess)] = getState.map { state => 
    (a, state)
  }

  final val handler: Flow[Message, Message, _] = Flow[Message]
    .mapAsync(1)(deserialize)
    .mapAsync(1) { command =>
      for {
        state <- getState()
        commandResult <- processCommand(command, state) 
      } yield (Responding(commandResult), state)
    }
    .merge(
      pushMessageSource.source
        .map(Telling(_))
        .mapAsync(1)(withState)
    )
    .prepend(Source.single(Starting).mapAsync(1)(withState))
    .via(
      Flow.fromGraph(KillSwitches.single[(Action, Sess)])
        .mapMaterializedValue { k =>
          killSwitch = k
          ()
        }
    )
    .concat(Source.single(Ending).mapAsync(1)(withState))
    .mapAsync(1) { case (action, state) =>
      for {
        (output, newState) <- processAction(action, state, connectionControl)
        _ <- if (newState != state) {
            setState(newState)
          } else {
            Future.successful(())
          }
      } yield output
    }
    .mapConcat(_.toList)
    .mapAsync(1)(serialize)
}

object CommandAndPushWebSocketHandler {
  trait PushMessageSource[Push] {
    val source: Source[Push, _]
  }

  case class ActorPushMessageSource[Push](
    pushMessageBufferSize: Int, 
    pushMessageOverflowStrategy: OverflowStrategy
  ) extends PushMessageSource[Push] {
      var actorRef: ActorRef = null
      val source = Source
        .actorRef[Push](pushMessageBufferSize, pushMessageOverflowStrategy)
        .mapMaterializedValue { r =>
          actorRef = r
          ()
        }
    }

  case class SourceQueuePushMessageSource[Push](
    pushMessageBufferSize: Int, 
    pushMessageOverflowStrategy: OverflowStrategy
  ) extends PushMessageSource[Push] {
      var pushMessageQueue: SourceQueue[Push] = null
      val source = Source
        .queue[Push](pushMessageBufferSize, pushMessageOverflowStrategy)
        .mapMaterializedValue { queue =>
          pushMessageQueue = queue
          ()
        }
    }

  class TextMesageDeserializationException extends Exception("This socket only accepts text messages.")

  def textMessageToString(message: Message)(implicit mat: ActorMaterializer): Future[String] = {
    message match {
      case m: TextMessage =>
        m.textStream
          // Collect the pieces of a split message, if it comes in parts.
          .runFold("")(_ ++ _)
      case _: BinaryMessage =>
        Future.failed(new TextMesageDeserializationException)
    }
  } 

  trait AtomicReferenceState[Sess] {
    val s = new AtomicReference(ConnectionState(user = None))
    def getState(): Future[ConnectionState] = Future.successful(s.get())
    def setState(newState: ConnectionState) = Future.successful(s.set(newState))
  }
}
