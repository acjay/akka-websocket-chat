package com.acjay.chatapp.service

import akka.actor.{ActorRef, ActorSystem}
import com.acjay.chatapp.ChatWebSocket
import java.util.concurrent.atomic.AtomicReference

case class PubSubService()(
  implicit 
  system: ActorSystem
) {
  val actorRefForUser = new AtomicReference(Map.empty[String, ActorRef])

  def publishEvent(event: ChatService.Event): Unit = {
    event.audience
      .flatMap(user => actorRefForUser.get.get(user))
      .foreach { actorRef =>
        actorRef ! (event match {
          case ChatService.UserLeftRoom(user, room, _) => 
            ChatWebSocket.SomeoneLeftRoom(room, user)
          case ChatService.UserJoinedRoom(user, room, _) => 
            ChatWebSocket.SomeoneJoinedRoom(room, user)
          case ChatService.NewMessage(roomName, speaker, message, _) => 
            ChatWebSocket.SomeoneSaid(roomName, speaker, message)
        })
      }
  }

  def subscribeForEvents(ref: ActorRef, user: String): Unit = {
    actorRefForUser.getAndUpdate(_ + (user -> ref))
  }

  def unsubscribeForEvents(user: String): Unit = {
    actorRefForUser.getAndUpdate(_ - user)
  }
}