import sbtdynver.DynVerPlugin.autoImport.*

ThisBuild / organization         := "com.matthewjones372"
ThisBuild / organizationHomepage := Some(uri("https://github.com/matthewjones372"))
ThisBuild / scalaVersion         := "3.8.4"

ThisBuild / publishTo := {
  Some("GitHub Package Registry" at s"https://maven.pkg.github.com/matthewjones372/starwars-api")
}

ThisBuild / credentials += Credentials(
  "GitHub Package Registry",
  "maven.pkg.github.com",
  sys.env.getOrElse("GITHUB_ACTOR", ""),
  sys.env.getOrElse("GITHUB_TOKEN", "")
)

dynverVTagPrefix                    := false // No v-prefix in the version tags
ThisBuild / dynverSonatypeSnapshots := true

ThisBuild / testFrameworks    := Seq(TestFramework("zio.test.sbt.ZTestFramework"))
ThisBuild / publish / skip    := true
ThisBuild / publishMavenStyle := true
Global / onChangedBuildSource := ReloadOnSourceChanges

// sbt 2 can restore a compile from its cache without running it, but scoverage writes its
// data directory as an undeclared side effect of that compile, so a restored compile leaves
// the instrumented classes with nowhere to write. Holding the cache in memory keeps it
// within a session, where target and the cache cannot disagree.
Global / cacheStores := Seq(new sbt.util.InMemoryActionCacheStore)

lazy val oneToOneClassMapping = "test->test;compile->compile"

lazy val root = (project in file("."))
  .settings(
    name := "swapi",
    // Entry points and the scraping script are executables rather than library code.
    coverageExcludedPackages := "<empty>;scripts\\..*"
  )
  .enablePlugins(GenerateOpenApiTask)
  .dependsOn(
    modules.map(_ % oneToOneClassMapping) *
  )
  .aggregate(modules *)

lazy val domain = Projects
  .create("domain")
  .settings(
    Libraries.zio,
    Libraries.zioSchema,
    Libraries.zioTest
  )
  .dependsOn(
    dynamicSorting % oneToOneClassMapping
  )

lazy val data = Projects
  .create("data")
  .settings(
    Libraries.zio,
    Libraries.zioConfig,
    Libraries.zioLogging,
    Libraries.zioHttp,
    Libraries.zioTest,
    Libraries.sql
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
    data   % oneToOneClassMapping,
    search % oneToOneClassMapping
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

lazy val loadTest = Projects
  .create("load-test")
  .settings(
    Libraries.zio,
    Libraries.zioHttp,
    Libraries.zioLogging,
    Libraries.zioTest,
    Libraries.proofload
  )
  .settings(publish / skip := true)
  .dependsOn(
    `http-api` % oneToOneClassMapping
  )

lazy val docs = project
  .in(file("mdoc-docs"))
  .enablePlugins(MdocPlugin)
  .settings(
    publish / skip := true,
    mdocIn         := file("docs/README.md"),
    mdocOut        := file("README.md"),
    // mdoc wraps each snippet in generated code that trips the unused-value warnings
    scalacOptions ~= (_.filterNot(Set("-Werror", "-Xfatal-warnings")))
  )
  .dependsOn(domain, search, dynamicSorting, client, data)

lazy val modules: Seq[ProjectReference] = Seq(domain, client, data, `http-api`, search, dynamicSorting)
