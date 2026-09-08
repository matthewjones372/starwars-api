import sbt._

object Dependencies {
  private val zioV        = "2.1.26"
  private val zioHttpV    = "3.0.0-RC9"
  private val zioSchemaV  = "1.3.0"
  private val zioCacheV   = "0.2.8"
  private val stubbyV     = "0.1.5"
  private val resilienceV = "0.10.5"
  private val zioLoggingV = "2.5.3"
  private val slf4jApiV   = "2.0.19"
  private val zioConfigV  = "4.0.8"

  private val magnumV         = "1.3.1"
  private val postgresV       = "42.7.13"
  private val hikariV         = "7.1.0"
  private val testcontainersV = "2.0.5"
  private val flywayV         = "13.5.0"

  val zio = "dev.zio" %% "zio" % zioV

  val zioConfig         = "dev.zio" %% "zio-config"          % zioConfigV
  val zioConfigMagnolia = "dev.zio" %% "zio-config-magnolia" % zioConfigV
  val zioConfigTypeSafe = "dev.zio" %% "zio-config-typesafe" % zioConfigV

  val zioSchema     = "dev.zio" %% "zio-schema"      % zioSchemaV
  val zioSchemaJson = "dev.zio" %% "zio-schema-json" % zioSchemaV

  val zioCache = "dev.zio" %% "zio-cache" % zioCacheV

  val zioLoggingSl4j = "dev.zio"  %% "zio-logging-slf4j" % zioLoggingV
  val sl4jApi        = "org.slf4j" % "slf4j-api"         % slf4jApiV
  val sl4jSimple     = "org.slf4j" % "slf4j-simple"      % slf4jApiV

  val resilience     = "nl.vroste" %% "rezilience"       % resilienceV
  val zioHttp        = "dev.zio"   %% "zio-http"         % zioHttpV
  val zioHttpTestKit = "dev.zio"   %% "zio-http-testkit" % zioHttpV % Test

  val zioTest         = "dev.zio" %% "zio-test"          % zioV % Test
  val zioTestSbt      = "dev.zio" %% "zio-test-sbt"      % zioV % Test
  val zioTestMagnolia = "dev.zio" %% "zio-test-magnolia" % zioV % Test

  val stubby = "io.github.kitlangton" %% "stubby" % stubbyV % Test

  val magnum   = "com.augustnagro" %% "magnum"     % magnumV
  val postgres = "org.postgresql"   % "postgresql" % postgresV
  val hikari   = "com.zaxxer"       % "HikariCP"   % hikariV

  val flyway         = "org.flywaydb" % "flyway-core"                % flywayV
  val flywayPostgres = "org.flywaydb" % "flyway-database-postgresql" % flywayV

  val testContainersPostgres = "org.testcontainers" % "testcontainers-postgresql" % testcontainersV % Test
}
