# Literate programming setup

As mentioned in the intro, this project uses literate programming in order to keep the explanatory text and the code in tight synchrony. Literate programming is kind of a niche technique, and the tooling is pretty varied. I was looking for something to meet the following needs:

- I want the literate source file to be plain Markdown.
- I want to be the literate source file to be a blog post first, which means I need to be able to code chunks out out of order and maybe hide extraneous details in appendix posts.

As it turned out, a project called [lmt](https://github.com/driusan/lmt) fit the bill.

The only catch is that it's written in Go, which means I had to incorporate Go into the build process for this project. I wanted to do so in a way that integrates smoothly with my SBT build, which required some finesse. This post will explain this process, itself using lmt to generate our `build.sbt` file.

## The build file template

My original SBT file was very minimal. Here it is:

```scala build.sbt
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
```

In order to set up for lmt, I'll add a few placeholders:


```scala build.sbt
<<<imports>>>

<<<lmt installation>>>

<<<key declarations>>>

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
    <<<key definitions>>
    <<<redefine compile task>>>
  )
```

My endpoint here was to make it so that we regenerate our Scala source files from lmt-ready blog posts automatically before compilation happens. We do this by:

```scala build.sbt "redefine compile task"
(Compile / compile) := ((Compile / compile) dependsOn generateSources).value
```

Now, in order to get to that endpoint, we've got to define the task `generateSources`. I don't want to get too deep into the theory of how SBT works, but basically, we define a graph of tasks and SBT figures out how to run them in order based on dependencies between these tasks. In our SBT file, we declare depedencies by calling `.value` on a task in the context of another task's definition.

Let's walk through the process of how we actually generate these files.

## Our input data

```scala build.sbt "key definitions"
goDirectory := baseDirectory.value / "go",
```

It is customary in SBT to define paths we'll need later as setting keys that reference [`File`](https://docs.oracle.com/javase/7/docs/api/java/io/File.html)s, all relative to `baseDirectory`, which will be the project's root directory. This `./go` directory in our project is git-ignored, and will be where we install lmt. But we can't simply make keys out of thin air, we need to declare them first.

```scala build.sbt "key declarations"
lazy val goDirectory = settingKey[File]("Subdirectory for Go dependencies (i.e. lmt)")
```

We'll also declare and define a setting for the Markdown sources to use for code generation. These must be specified in order that lmt will process them, which is important if one file overwrites a block defined in another file

On to the actual work.

```scala build.sbt "key definitions" +=
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
```

Here, we define the path to the lmt executable, installing it if it doesn't exist. As mentioned above, we depend on the `goDirectory` task by simply 