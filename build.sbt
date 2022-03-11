name := "akka-actor-interaction-sample"
version := "1.0"
scalaVersion := "2.13.7"
semanticdbEnabled := true
semanticdbVersion := scalafixSemanticdb.revision
scalacOptions ++= Seq(
  "-Ywarn-unused"
)

val root = (project in file("."))
  .settings(
    libraryDependencies ++= Seq(
      "com.typesafe.akka" %% "akka-actor-typed"         % Versions.akka,
      "com.typesafe.akka" %% "akka-persistence-typed"   % Versions.akka,
      "com.typesafe.akka" %% "akka-persistence-testkit" % Versions.akka % Test,
      "org.slf4j"          % "slf4j-api"                % Versions.slf4j.api,
      "ch.qos.logback"     % "logback-classic"          % Versions.logback excludeAll (
        ExclusionRule("org.slf4j")
      )
    )
  )

addCommandAlias("fmt", ";scalafmtAll;scalafmtSbt;scalafix RemoveUnused")
