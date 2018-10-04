package com.acjay.chatapp

import akka.actor.{ActorRef, ActorSystem}
import akka.http.scaladsl.model.ws.{Message, TextMessage}
import akka.stream.{ActorMaterializer, OverflowStrategy}
import com.acjay.lib.CommandAndPushWebSocketHandler
import com.acjay.chatapp.service.{ChatService, UserService}
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.{ExecutionContextExecutor, Future}

case class ChatWebSocket(
  userService: UserService, 
  chatService: ChatService,
  subscribeForEvents: (ActorRef, String) => Unit,
  unsubscribeForEvents: String => Unit
)(
  implicit
  system: ActorSystem,
  mat: ActorMaterializer,
  val ec: ExecutionContextExecutor
) extends CommandAndPushWebSocketHandler {
  import CommandAndPushWebSocketHandler._
  import ChatWebSocket._
  import ChatService._
  import UserService._

  type Cmd = ClientCommand
  type Res = ClientCommandResult
  type Push = PushNotification
  type Out = ClientUpdate
  type Sess = ConnectionState

  val pushMessageSource = ActorPushMessageSource(20, OverflowStrategy.dropHead)

  val s = new AtomicReference(ConnectionState(user = None))
  def getState(): Future[ConnectionState] = Future.successful(s.get())
  def setState(newState: ConnectionState) = Future.successful(s.set(newState))

  def deserialize(request: Message): Future[ChatWebSocket.ClientCommand] = {
    CommandAndPushWebSocketHandler.textMessageToString(request)
      .map(ClientCommand.fromString)
  }

  def processCommand(command: ClientCommand, state: Sess): Future[ClientCommandResult] = state match {
    case ConnectionState(None) =>
      command match {
        case LogIn(username, password) =>
          for {
            authResult <- userService.validate(username, password)
          } yield {
            authResult match {
              case AuthSuccess => SetUser(username)
              case AuthFailure => ClientError("Authentication failed.")
            }
          }
        case _ =>
          Future.successful(ClientError("Only LogIn comamnds are accepted until you authenticate."))
      }
      
    case ConnectionState(Some(user)) =>
      command match {
        case LogIn(_, _) =>
          Future.successful(ClientError("Already logged in!"))
        case LogOut => 
          chatService.endSession(user).map {
            case _ => Disconnect
          }
        case JoinRoom(roomName) => 
          chatService.joinRoom(roomName, user).map {
            case _ => Ok
          }
      
        case LeaveRoom(roomName) =>
          chatService.leaveRoom(roomName, user).map {
            case _ => Ok
          }
        case Speak(roomName, message) =>
          chatService.newMessage(roomName, user, message).map {
            case _ => Ok
          }

        case ErroneousCommand(text) =>
          Future.successful(ClientError(s"Unrecognized command: $text"))
      }
  } 

  def processAction(
    action: Action, 
    state: Sess, 
    connectionControl: ConnectionControl
  ): Future[(Option[Out], Sess)] = action match {
    case Starting =>
      Future.successful((Some(LoginChallenge), state))

    case Responding(SetUser(user)) =>
      subscribeForEvents(connectionControl.pushMessageSource.actorRef, user)
      Future.successful((Some(Welcome), state.copy(user = Some(user))))

    case Responding(ClientError(reason)) =>
      Future.successful((Some(Error(reason)), state))

    case Responding(Disconnect) =>
      connectionControl.shutdown()
      Future.successful((Some(Goodbye), state))

    case Telling(s: SomeoneJoinedRoom) if state.user.contains(s.person) =>
      Future.successful((Some(YouJoinedRoom(s.roomName)), state))

    case Telling(s: SomeoneLeftRoom) if state.user.contains(s.person) =>
      Future.successful((Some(YouLeftRoom(s.roomName)), state))

    case Telling(update) =>
      Future.successful((Some(update), state))

    case Ending =>
      state.user.foreach(unsubscribeForEvents)
      Future.successful((None, state))
  }

  def serialize(output: ClientUpdate): Future[Message] = {
    Future.successful(TextMessage(ClientUpdate.asString(output)))
  }
}

object ChatWebSocket {
  sealed trait ClientCommand
  case class LogIn(username: String, password: String) extends ClientCommand
  object LogIn { val r = raw"Log in: ([A-Za-z0-9]+) ([A-Za-z0-9]+)".r }
  case object LogOut extends ClientCommand { val r = raw"Log out".r }
  case class JoinRoom(roomName: String) extends ClientCommand
  object JoinRoom { val r = raw"Join room: ([A-Za-z0-9]+)".r }
  case class LeaveRoom(roomName: String) extends ClientCommand
  object LeaveRoom { val r = raw"Leave room: ([A-Za-z0-9]+)".r }
  case class Speak(roomName: String, message: String) extends ClientCommand
  object Speak { val r = raw"Speak: (.+)".r }
  case class ErroneousCommand(text: String) extends ClientCommand

  sealed trait ClientCommandResult
  case class ClientError(reason: String) extends ClientCommandResult
  case object Disconnect extends ClientCommandResult
  case class SetUser(user: String) extends ClientCommandResult
  case object Ok extends ClientCommandResult

  sealed trait ClientUpdate

  // Everything that comes in as a push notification is intended to be a 
  // message for the user, so this simplifies things a bit.
  sealed trait PushNotification extends ClientUpdate

  case object LoginChallenge extends ClientUpdate
  case object Welcome extends ClientUpdate 
  case object Goodbye extends ClientUpdate
  case class YouJoinedRoom(roomName: String) extends ClientUpdate
  case class YouLeftRoom(roomName: String) extends ClientUpdate
  case class SomeoneJoinedRoom(roomName: String, person: String) extends PushNotification
  case class SomeoneLeftRoom(roomName: String, person: String) extends PushNotification
  case class SomeoneSaid(roomName: String, speaker: String, message: String) extends PushNotification
  case class Error(reason: String) extends ClientUpdate
  
  // Our serialization layer. Using plain strings to keep it simple.

  object ClientCommand {
    def fromString(str: String): ClientCommand = str match {
      case LogIn.r(username, password) => LogIn(username, password)
      case LogOut.r() => LogOut
      case JoinRoom.r(roomName) => JoinRoom(roomName)
      case LeaveRoom.r(roomName) => LeaveRoom(roomName)
      case Speak.r(roomName, message) => Speak(roomName, message)
      case other => ErroneousCommand(other)
    }
  }

  object ClientUpdate {
    def asString(c: ClientUpdate): String = c match {
      case LoginChallenge => "Welcome to the server! Please log in."
      case Welcome => "Successfully logged in!"
      case Goodbye => "See you next time."
      case YouJoinedRoom(roomName) => s"You joined room $roomName."
      case YouLeftRoom(roomName) => s"You left room $roomName."
      case SomeoneJoinedRoom(roomName, person) => s"$person joined room $roomName."
      case SomeoneLeftRoom(roomName, person) => s"$person left room $roomName."
      case SomeoneSaid(roomName, speaker, message) => s"$roomName/$speaker: $message"
      case Error(reason) => s"Error: $reason"
    }
  }

  case class ConnectionState(user: Option[String])
}