package com.acjay.chatapp.service

import scala.concurrent.Future

case class UserService() {
  import UserService._

  // Just making the point of how the interface of validation would work.
  def validate(username: String, password: String): Future[AuthResult] = {
    if (username == password) {
      Future.successful(AuthSuccess)
    } else {
      Future.successful(AuthFailure)
    }
  }
}

object UserService {
  sealed trait AuthResult
  case object AuthSuccess extends AuthResult
  case object AuthFailure extends AuthResult
}