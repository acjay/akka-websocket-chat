package com.acjay.chatapp

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.stream.ActorMaterializer
import scala.io.StdIn
import com.acjay.chatapp.service.{ ChatService, PubSubService, UserService }

object WebServer {
  def main(args: Array[String]) {

    implicit val system = ActorSystem("my-system")
    implicit val materializer = ActorMaterializer()
    implicit val executionContext = system.dispatcher

    val pubSub = PubSubService()
    val userService = UserService()
    val chatService = ChatService(
      pubSub.publishEvent
    )

    val route =
      path("socket") {
        handleWebSocketMessages(ChatWebSocket(
          userService,
          chatService,
          pubSub.subscribeForEvents,
          pubSub.unsubscribeForEvents
        ).handler)
      }

    val bindingFuture = Http().bindAndHandle(route, "localhost", 8080)

    println(s"Server online at http://localhost:8080/\nPress RETURN to stop...")
    StdIn.readLine() // let it run until user presses return
    bindingFuture
      .flatMap(_.unbind()) // trigger unbinding from the port
      .onComplete(_ => system.terminate()) // and shutdown when done
  }
}