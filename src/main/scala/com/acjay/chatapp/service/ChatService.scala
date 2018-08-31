package com.acjay.chatapp.service

import scala.concurrent.Future

case class ChatService(
  publishEvent: ChatService.Event => Unit
) {
  import ChatService._

  var data = Seq.empty[Room]

  protected def membersForRoom(roomName: String): Seq[String] = data
    .find(_.name == roomName)
    .map(_.users)
    .getOrElse(Seq.empty)

  protected def removeUserFromRoom(user: String, limitedToRoomName: Option[String]) = {
    data = data
      .map { room =>
        if (limitedToRoomName.forall(_ == room.name) && room.users.contains(user)) {
          publishEvent(UserLeftRoom(user, room.name, membersForRoom(room.name)))
          room.copy(users = room.users.filterNot(_ == user))
        } else {
          room
        }
      }
      .filterNot(_.users.isEmpty)
  }

  def joinRoom(roomName: String, user: String): Future[Unit] = {
    val newRoom = data
      .find(_.name == roomName)
      .map(room => room.copy(users = room.users :+ user))
      .getOrElse(Room(roomName, Seq(user)))
    
    data = data.filterNot(_.name == roomName) :+ newRoom

    publishEvent(UserJoinedRoom(user, roomName, membersForRoom(roomName)))

    Future.successful(())
  }

  def leaveRoom(roomName: String, user: String): Future[Unit] = {
    removeUserFromRoom(user, Some(roomName))
    Future.successful(())
  }

  def endSession(user: String): Future[Unit] = {
    removeUserFromRoom(user, None)
    Future.successful(())
  }

  def newMessage(roomName: String, user: String, message: String): Future[Unit] = {
    publishEvent(NewMessage(roomName, user, message, membersForRoom(roomName)))
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
  case class NewMessage(room: String, user: String, message: String, audience: Seq[String]) extends Event
}