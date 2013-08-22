import sbt._
import Keys._
import play.Project._

object ApplicationBuild extends Build {

  val appName         = "playztream"
  val appVersion      = "1.0-SNAPSHOT"

  val appDependencies = Seq(
      "org.scalaz.stream" %% "scalaz-stream"       % "0.1-SNAPSHOT",
      "org.specs2"        %% "specs2"              % "1.13"        % "test",
      "junit"              % "junit"               % "4.8"         % "test"
  )

  val sonatypeRepo = Seq(
    "Sonatype OSS Releases" at "http://oss.sonatype.org/content/repositories/releases/",
    "Sonatype OSS Snapshots" at "http://oss.sonatype.org/content/repositories/snapshots/"    
  )

  val main = play.Project(appName, appVersion, appDependencies).settings(
    resolvers ++= sonatypeRepo
  )

}
