name:="playzstream"

version:="1.1-SNAPSHOT"

libraryDependencies ++= Seq(
  "org.scalaz.stream" %% "scalaz-stream" % "0.1",
  "org.specs2" %% "specs2" % "1.13" % "test",
  "junit" % "junit" % "4.8" % "test"
)

play.Project.playScalaSettings
