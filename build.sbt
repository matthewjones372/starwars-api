ThisBuild / version := "0.1.0-SNAPSHOT"

ThisBuild / scalaVersion := "3.4.2"

lazy val root = (project in file("."))
  .settings(
    name             := "swapi",
    idePackagePrefix := Some("com.jones")
  )

libraryDependencies ++= Seq(
  "dev.zio"              %% "zio"               % "2.1.6",
  "dev.zio"              %% "zio-http"          % "3.0.0-RC9",
  "dev.zio"              %% "zio-json"          % "0.7.1",
  "dev.zio"              %% "zio-prelude"       % "1.0.0-RC27",
  "dev.zio"              %% "zio-schema-json"   % "1.3.0",
  "dev.zio"              %% "zio-cache"         % "0.2.3",
  "dev.zio"              %% "zio-concurrent"    % "2.1.6",
  "nl.vroste"            %% "rezilience"        % "0.9.4",
  "dev.zio"              %% "zio-http-testkit"  % "3.0.0-RC9" % Test,
  "dev.zio"              %% "zio-test"          % "2.1.5"     % Test,
  "dev.zio"              %% "zio-test-sbt"      % "2.1.6"     % Test,
  "dev.zio"              %% "zio-test-magnolia" % "2.1.6"     % Test,
  "io.github.kitlangton" %% "stubby"            % "0.1.2"     % Test
)

testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework")
