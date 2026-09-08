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

// Deliberately outside `modules`: the load test resolves Kestrel from the local
// maven repository, which a clean CI checkout does not have. Root aggregation
// never reaches it, so `sbt test` is unaffected and `sbt "load-test/run"` is how
// you ask for it (the project id is `load-test`). See load-test/README.md.
lazy val loadTest = Projects
  .create("load-test")
  .settings(
    Libraries.zio,
    Libraries.zioHttp,
    Libraries.zioLogging,
    Libraries.kestrel
  )
  .settings(
    publish / skip := true,
    // The generator and the target must not share a heap, so the sweep starts
    // the server in a JVM of its own rather than in this one.
    run / fork := true,
    // sweep.sh launches both JVMs itself, so it needs the classpath as a file
    // rather than an sbt session holding one of them.
    TaskKey[Unit]("writeClasspath") := {
      // sbt 2 hands back virtual file references, so the converter is what
      // turns a classpath into paths another JVM can be started with.
      val converter = fileConverter.value
      val entries   = (Runtime / fullClasspath).value.map(entry => converter.toPath(entry.data))
      // Under the module rather than sbt 2's out/ tree, so sweep.sh has a
      // fixed path to read and does not have to know the scala version.
      val out       = baseDirectory.value / "target" / "load-test-cp.txt"
      IO.createDirectory(out.getParentFile)
      IO.write(out, entries.mkString(java.io.File.pathSeparator))
      streams.value.log.info(s"wrote $out")
    }
  )
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
