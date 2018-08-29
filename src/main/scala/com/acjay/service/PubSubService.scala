case class PubSub()(
  implicit 
  system: ActorSystem
) {
  val actorRefForUser = AtomicReference(Map.empty[String, ActorRef])

  def publishEvent(event: ChatService.Event): Unit = {
    event.audience
      .flatMap(user => actorRefForUser.get(user))
      .foreach { actorRef =>
        actorRef ! (event match {
          case UserLeftRoom(user, room, _) => OtherPersonLeftRoom(room, user)
          case UserJoinedRoom(user, room, _) => OtherPersonJoinedRoom(room, user)
          case NewMessage(roomName, speaker, message, _) => SomeoneSaid(roomName, speaker, message)
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