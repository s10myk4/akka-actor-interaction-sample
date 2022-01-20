inThisBuild(
  List(
    scalaVersion := "2.13.7",
    semanticdbEnabled := true,
    semanticdbVersion := scalafixSemanticdb.revision,
    scalacOptions ++= Seq(
      "-Ywarn-unused"
    )
  )
)

name := "akka-actor-interaction-sample"
version := "1.0"

val root = (project in file("."))
  .settings(
    libraryDependencies ++= Seq(
      "com.typesafe.akka" %% "akka-actor-typed" % Versions.akka.actor
    )
  )

addCommandAlias("fmt", ";scalafmtAll;scalafmtSbt;scalafix RemoveUnused")
