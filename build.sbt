name := "sea-battle-zio"

ThisBuild / version := "0.4"
ThisBuild / organization := "me.guliaev"
ThisBuild / scalaVersion := "2.13.10"

lazy val app = (project in file(".")).settings(
  Docker / packageName := packageName.value,
  Docker / version := version.value,
  Docker / dockerExposedPorts := Seq(8080),
  assembly / assemblyJarName := s"${name.value}-${version.value}.jar",
  assembly / mainClass := Some("me.guliaev.seabattle.Application"),
  assemblyMergeStrategy := { _ => MergeStrategy.first },
  libraryDependencies ++= (Seq(
    "dev.zio" %% "zio" % Versions.zio,
    "dev.zio" %% "zio-http" % Versions.zioHttp,
    "com.github.pureconfig" %% "pureconfig" % Versions.pureConfig,
    "org.scalatest" %% "scalatest" % Versions.scalaTest % "test",
    "org.scalamock" %% "scalamock" % Versions.scalaMock % "test"
  ) ++ Seq(
    "io.circe" %% "circe-core",
    "io.circe" %% "circe-generic",
    "io.circe" %% "circe-parser",
    "io.circe" %% "circe-generic-extras"
  ).map(_ % Versions.circe))
)

enablePlugins(JavaAppPackaging)
enablePlugins(DockerPlugin)
