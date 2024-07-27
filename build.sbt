ThisBuild / version := "0.1.0-SNAPSHOT"

ThisBuild / scalaVersion := "3.4.2"

lazy val root = (project in file("."))
  .settings(
    name             := "swapi",
    idePackagePrefix := Some("com.jones")
  )

libraryDependencies ++= Seq(
  "dev.zio" %% "zio"               % "2.1.6",
  "dev.zio" %% "zio-http"          % "3.0.0-RC9",
  "dev.zio" %% "zio-json"          % "0.7.1",
  "dev.zio" %% "zio-prelude"       % "1.0.0-RC27",
  "dev.zio" %% "zio-test"          % "2.1.5" % Test,
  "dev.zio" %% "zio-test-sbt"      % "2.1.6" % Test,
  "dev.zio" %% "zio-test-magnolia" % "2.1.6" % Test
)

testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework")
