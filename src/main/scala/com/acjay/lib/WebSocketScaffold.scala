package com.acjay.lib

import akka.actor.ActorRef
import akka.http.scaladsl.model.ws.{BinaryMessage, Message, TextMessage}
import akka.stream.scaladsl.{Flow, Source}
import akka.stream.{ActorMaterializer, KillSwitch, KillSwitches, OverflowStrategy}
import scala.concurrent.{ExecutionContextExecutor, Future}

trait WebSocketScaffold {
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

  // Configuration
  def pushMessageBufferSize: Int
  def pushMessageOverflowStrategy: OverflowStrategy

  def getState(): Future[Sess]
  def setState(state: Sess): Future[Unit]
  def deserialize(command: Message): Future[Option[Cmd]]
  def processCommand(command: Cmd, state: Sess): Future[Res]
  def processAction(action: Action, state: Sess, connectionControl: WebSocketScaffold.ConnectionControl): Future[(Out, Sess)]
  def serialize(output: Out): Future[Option[Message]]

  // Internal state. These are constants, practically speaking, because they 
  // will be set when the Flow is materialized, and then never mutated.
  private var pushMessageReceiver: ActorRef = null
  private var killSwitch: KillSwitch = null

  final protected def connectionControl = WebSocketScaffold.ConnectionControl(
    pushMessageReceiver,
    killSwitch
  )

  final private def withState[A](a: A): Future[(A, Sess)] = getState.map { state => 
    (a, state)
  }

  final def handler: Flow[Message, Message, _] = Flow[Message]
    .mapAsync(1)(deserialize)
    .mapConcat(_.toList)
    .mapAsync(1) { command =>
      for {
        state <- getState()
        commandResult <- processCommand(command, state) 
      } yield (Responding(commandResult), state)
    }
    .merge(
      Source
        .actorRef(pushMessageBufferSize, pushMessageOverflowStrategy)
        .mapMaterializedValue { actorRef =>
          pushMessageReceiver = actorRef
          ()
        }
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
        _ <- setState(newState)
      } yield output
    }
    .mapAsync(1)(serialize)
    .mapConcat(_.toList)
}

object WebSocketScaffold {
  case class ConnectionControl(
    pushMessageReceiver: ActorRef,
    killSwitch: KillSwitch
  )

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
}