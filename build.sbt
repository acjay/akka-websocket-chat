import Dependencies._
import scala.sys.process._

def installLmt(goDir: File) = {
  Process(
    List("go", "get", "-v", "github.com/driusan/lmt"),
    goDir.toString, 
    "GOPATH" -> goDir.toString
  ) !
}

lazy val goDirectory = settingKey[File]("Subdirectory for Go dependencies (i.e. lmt)")

lazy val literateSources = taskKey[Seq[File]]("Paths to all Markdown files used to genereate Scala sources.")

lazy val lmtExecutable = taskKey[File]("Path to lmt binary. Will install lmt, if necessary.")

lazy val reinstallLmt = taskKey[Unit]("Force an install of lmt.")

lazy val generateSources = taskKey[Unit]("Run lmt to generate Scala source files from literate Markdown sources.")

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
    ),
    goDirectory := baseDirectory.value / "go",
    literateSources := {
      val sourceListingFile = baseDirectory / "literate_source_files.txt"

      val sources = scala.io.Source.fromFile(sourceListingFile)
        .getLines
        .toList
        .map(new java.io.File(_))
      
      val nonexistentSources = sources
        .collect { case source if !soure.exists => source)

      if (nonexistentSources.nonEmpty) {
        throw new Exception(
          s"The following literate Markdown sources were not found:\n" + 
          nonexistentSources.mkstring("\n")
        )
      }

      sources
    },
    lmtExecutable := {
      val goDir = goDirectory.value
      val log = streams.value.log
      val path = goDir / "bin" / "lmt"
      if (!path.exists) {
        log.info("Local lmt executable not found. Installing...")
        installLmt(goDir)
      }
      path
    },
    reinstallLmt := {
      installLmt(goDirectory.value)
    },
    generateSources := {
      val command = Seq(lmtExecutable.value.toString) ++
        literateSources.value.map(_.toString)
    
      command !
    },
    (Compile / compile) := ((Compile / compile) dependsOn generateSources).value
  )
