case class ChatService(
  publishEvent: Event => Unit
) {
  import ChatService._

  var data = Seq[Room].empty

  protected def removeUserFromRoom(user: String, limitedToRoomName: Option[String]) = {
    data = data
      .map { room =>
        if (limitedToRoomName.forall(_ == room.name) && room.contains(username)) {
          publishEvent(UserLeftRoom(username, room))
          room.copy(users = users.filterNot(_ == username))
        } else {
          room
        }
      }
      .filterNot(_.users.empty)
  }

  def joinRoom(roomName: String, user: String): Future[Unit] = {
    val newRoom = data
      .find(_.name == roomName)
      .map(room => room.copy(users = room.users :+ user))
      .getOrElse(Room(roomName, Seq(user), Seq.empty))
    
    data = data.filterNot(_.name == roomName) :+ newRoom

    publishEvent(UserJoinedRoom(user, roomName))

    Future.successful(())
  }

  def leaveRoom(roomName: String, user: String): Future[Unit] = {
    removeUserFromRoom(user, Some(roomName))
    Future.successful(())
  }

  def endSession(username: String): Future[Unit] = {
    removeUserFromRoom(user, None)
    Future.successful(())
  }

  def newMessage(roomName: String, user: String, message: String): Future[Unit] = {
    publishEvent(NewMessaage(roomName, user, message))
    Future.successful(())
  }
}

object ChatService {
  case class Room(
    name: String,
    users: Seq[String]
  )

  sealed trait Event { def audience: Seq[String] }
  case class UserLeftRoom(user: String, room: String, audience: Seq[String]) extends Event
  case class UserJoinedRoom(user: String, room: String, audience: Seq[String]) extends Event
  case class NewMessaage(room: String, user: String, message: String, audience: Seq[String]) extends Event
}