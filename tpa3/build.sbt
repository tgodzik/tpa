name := "tpa3-io.tpa.raft-exercise"

version := "2.4.4"

scalaVersion := "2.11.7"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor" % "2.4.4",
  "com.typesafe.akka" %% "akka-testkit" % "2.4.4",
  "org.scalatest" %% "scalatest" % "2.2.6" % "test"
)