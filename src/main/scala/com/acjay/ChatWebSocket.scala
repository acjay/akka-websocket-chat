package com.acjay

import akka.stream.OverflowStrategy
import com.acjay.lib.WebSockets
import java.util.concurrent.atomic.AtomicReference

case class ChatWebSocket(
  userService: UserService, 
  chatService: ChatService,
  subscribeForEvents: (ActorRef, String) => Unit,
  unsubscribeForEvents: String => Unit
)(
  implicit
  system: ActorSystem,
  ec: ExecutionContextExecutor
) extends WebSockets {
  import ChatWebSocket._

  type Cmd = ClientCommand
  type Res = ClientCommandResult
  type Push = PushNotification
  type Out = ClientUpdate
  type Sess = ConnectionState

  val pushMessageBufferSize = 20
  val pushMessageOverflowStrategy = OverflowStrategy.dropHead

  val s = new AtomicReference(ConnectionState(user = None))
  def getState(): Future[ConnectionState] = Future.successful(s.get())
  def setState(newState: ConnectionState) = Future.successful(s.set(newState))

  def deserialize(request: Message): Future[Option[ChatWebSocket.ClientCommand]] = {
    WebSockets.textMessageToString(request).map(ClientCommand.fromString)
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
              case AuthFailure => Disconnect("Authentication failed.")
            }
          }
        case _ =>
          Future.successful(Disconnect("Only LogIn comamnds are accepted until you authenticate."))
      
    case ConnectionState(Some(username)) =>
      command match {
        case LogIn(_, _) =>
          Future.successful(ClientError("Already logged in!"))
        case LogOut => chatService.endSession(username).map {
          case _ => Future.successful(Disconnect("Bye bye!"))
        }
        case JoinRoom(roomName) => 
          chatService.joinRoom(roomName, user).map {
            case _ => Future.successful(Ok)
          }
      
        case LeaveRoom(roomName) =>
          chatService.leaveRoom(roomName, user).map {
            case _ => Future.successful(Ok)
          }
        case Speak(roomName, message) =>
          chatService.newMessage(roomName, user, message).map {
            case _ => Future.successful(Ok)
          }
      }
    }
  } 

  def processAction(
    action: Action, 
    state: Sess, 
    connectionControl: WebSockets.ConnectionControl
  ): Future[(Out, Sess)] = action match {
    case Starting =>
      Future.successful((Challenge, state))

    case Responding(SetUser(user)) =>
      subscribeForEvents(connectionControl.actorRef, user)
      Future.successful((Welcome, state.copy(user = Some(user))))

    case Responding(Disconnect(goodbyeMessage)) =>
      connectionControl.killSwitch.shutdown()
      Future.successful((Goodbye(goodbyeMessage), state))

    case Telling(s: SomeoneJoinedRoom) if state.user.contains(s.person) =>
      Future.successful((YouJoinedRoom(s.roomName)))

    case Telling(s: SomeoneLeftRoom) if state.user.contains(s.person) =>
      Future.successful((YouLeftRoom(s.roomName)))

    case Telling(update) =>
      Future.successful((update, state))

    case Ending =>
      state.user.forall(unsubscribeForEvents)
      Future.successful((Noop, state))
  }

  def serialize(output: ClientUpdate): Future[Option[Message]] = {
    Future.successful(ClientUpdate.asString(output).map(TextMessage.strict))
  }
}

object ChatWebSocket {
  sealed trait ClientCommand
  case class LogIn(username: String, password: String) extends ClientCommand
  object LogIn { val r = raw"Log in: ([A-Za-z0-9]+) ([A-Za-z0-9]+)".r }
  case object LogOut extends ClientCommand
  object LogOut { val r = raw"Log out".r }
  case class JoinRoom(roomName: String) extends ClientCommand
  object JoinRoom { val r = raw"Join room: ([A-Za-z0-9]+)".r }
  case class LeaveRoom(roomName: String) extends ClientCommand
  object LeaveRoom { val r = raw"Leave room: ([A-Za-z0-9]+)".r }
  case class Speak(roomName: String, message: String) extends ClientCommand
  object Speak { val r = raw"Speak: (.+)".r }

  sealed trait ClientCommandResult
  case class Disconnect(reason: String) extends ClientCommandResult
  case class Ok extends ClientCommandResult

  sealed trait PushNotification

  sealed trait ClientUpdate

  case object Welcome extends ClientUpdate 
  case object Goodbye extends ClientUpdate
  case class YouJoinedRoom(roomName: String) extends ClientUpdate
  case class YouLeftRoom(roomName: String) extends ClientUpdate
  case class SomeoneJoinedRoom(roomName: String, person: String) extends ClientUpdate with PushNotification
  case class SomeoneLeftRoom(roomName: String, person: String) extends ClientUpdate with PushNotification
  case class SomeoneSaid(roomName: String, speaker: String, message: String) extends ClientUpdate with PushNotification
  case object Noop extends ClientUpdate
  
  // Our serialization layer. Using plain strings to keep it simple.

  object ClientCommand {
    def fromString(str: String): Option[ClientCommand] = str match {
      case LogIn.r(username, password) => Some(LogIn(username, password))
      case LogOut.r => Some(LogOut)
      case JoinRoom.r(roomName) => Some(JoinRoom(roomName))
      case LeaveRoom.r(roomName) => Some(LeaveRoom(roomName))
      case Speak.r(roomName, message) => Some(Speak(roomName, message))
    }
  }

  object ClientUpdate {
    def asString(c: ClientUpdate): Option[String] = c match {
      case Welcome => Some("Welcome to the server! Please log in.")
      case Goodbye => Some("See you next time.")
      case YouJoinedRoom(roomName) => Some(s"You joined room $roomName.")
      case YouLeftRoom(roomName) => Some(s"You left room $roomName.")
      case SomeoneJoinedRoom(roomName, person) => Some(s"$person joined room $roomName.")
      case SomeoneLeftRoom(roomName, person) => Some(s"$person left room $roomName.")
      case SomeoneSaid(roomName, speaker, message) => Some(s"$roomName/$person: $message")
      case Noop => None
    }
  }

  case class ConnectionState(username: Option[String])
}