import Dependencies._

lazy val root = (project in file(".")).
  settings(
    inThisBuild(List(
      organization := "com.example",
      scalaVersion := "2.12.6",
      version      := "0.1.0-SNAPSHOT"
    )),
    name := "akka-websocket",
    libraryDependencies ++= Seq(
      "com.typesafe.akka" %% "akka-http"   % "10.1.4" ,
      "com.typesafe.akka" %% "akka-stream" % "2.5.12",
      scalaTest % Test
    )
  )
