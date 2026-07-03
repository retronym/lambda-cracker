ThisBuild / scalaVersion := "3.7.1"

// Pure-Java agent. Everything reachable from code injected into java.base lives in
// lambdacracker.boot and must have no dependencies beyond java.base itself.
lazy val agent = (project in file("agent"))
  .settings(
    name := "lambda-cracker-agent",
    autoScalaLibrary := false,
    crossPaths := false,
    Compile / javacOptions ++= Seq("--release", "25", "-g"),
    Compile / packageBin / packageOptions += Package.ManifestAttributes(
      "Premain-Class" -> "lambdacracker.LambdaCrackerAgent",
      "Can-Retransform-Classes" -> "true"
    )
  )

lazy val demo = (project in file("demo"))
  .settings(
    name := "lambda-cracker-demo",
    Compile / javacOptions ++= Seq("--release", "25", "-g"),
    fork := true,
    run / javaOptions += s"-javaagent:${(agent / Compile / packageBin).value.getAbsolutePath}"
  )
