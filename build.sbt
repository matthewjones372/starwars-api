import sbtdynver.DynVerPlugin.autoImport.*

ThisBuild / organization         := "com.matthewjones372"
ThisBuild / name                 := "starwars-api"
ThisBuild / organizationHomepage := Some(url("https://github.com/matthewjones372"))
ThisBuild / scalaVersion         := "3.4.2"

ThisBuild / publishTo := {
  Some("GitHub Package Registry" at s"https://maven.pkg.github.com/matthewjones372/starwars-api")
}

ThisBuild / credentials += Credentials(
  "GitHub Package Registry",
  "maven.pkg.github.com",
  sys.env.getOrElse("GITHUB_ACTOR", ""),
  sys.env.getOrElse("GITHUB_TOKEN", "")
)

publishMavenStyle := true

dynverVTagPrefix                    := false // No v-prefix in the version tags
ThisBuild / dynverSonatypeSnapshots := true

ThisBuild / testFrameworks    := Seq(TestFramework("zio.test.sbt.ZTestFramework"))
ThisBuild / publish / skip    := true
ThisBuild / publishMavenStyle := true
Global / onChangedBuildSource := ReloadOnSourceChanges

lazy val oneToOneClassMapping = "test->test;compile->compile"

lazy val root = (project in file("."))
  .settings(
    name := "swapi"
  )
  .enablePlugins(GenerateOpenApiTask)
  .dependsOn(
    modules.map(_ % oneToOneClassMapping): _*
  )
  .aggregate(modules: _*)

lazy val domain = Projects
  .create("domain")
  .settings(
    Libraries.zioSchema,
    Libraries.zioTest
  )

lazy val data = Projects
  .create("data")
  .settings(
    Libraries.zio,
    Libraries.zioConfig,
    Libraries.zioLogging,
    Libraries.zioHttp,
    Libraries.zioTest
  )
  .dependsOn(
    domain % oneToOneClassMapping
  )

lazy val `http-api` = Projects
  .create("http-api")
  .settings(
    Libraries.zio,
    Libraries.zioHttp,
    Libraries.zioLogging,
    Libraries.zioConfig,
    Libraries.zioTest
  )
  .dependsOn(
    domain % oneToOneClassMapping,
    data   % oneToOneClassMapping
  )

lazy val dynamicSorting = Projects
  .create("multi-sort")
  .settings(Libraries.zioTest)
  .settings(publish / skip := false)

lazy val client = Projects
  .create("api-client")
  .settings(
    Libraries.zioHttp,
    Libraries.zioCache,
    Libraries.resilience,
    Libraries.zioConfig,
    Libraries.zioLogging,
    Libraries.zioTest
  )
  .settings(
    publish / skip := false
  )
  .dependsOn(
    domain     % oneToOneClassMapping,
    `http-api` % oneToOneClassMapping
  )

lazy val search = Projects
  .create("search")
  .settings(
    Libraries.zio,
    Libraries.zioTest
  )
  .dependsOn(
    domain % oneToOneClassMapping
  )

lazy val modules: Seq[ProjectReference] = Seq(domain, client, data, `http-api`, search, dynamicSorting)
