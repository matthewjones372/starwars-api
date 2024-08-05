import sbt._

object Dependencies {
  private val zioV        = "2.1.6"
  private val zioHttpV    = "3.0.0-RC9"
  private val zioSchemaV  = "1.3.0"
  private val zioJsonV    = "0.7.1"
  private val zioCacheV   = "0.2.3"
  private val stubbyV     = "0.1.2"
  private val resilienceV = "0.9.4"

  val zio     = "dev.zio" %% "zio"      % zioV
  val zioJson = "dev.zio" %% "zio-json" % zioJsonV

  val zioSchema     = "dev.zio" %% "zio-schema"      % zioSchemaV
  val zioSchemaJson = "dev.zio" %% "zio-schema-json" % zioSchemaV

  val zioCache      = "dev.zio" %% "zio-cache"      % zioCacheV
  val zioConcurrent = "dev.zio" %% "zio-concurrent" % zioV

  val resilience     = "nl.vroste" %% "rezilience"       % resilienceV
  val zioHttp        = "dev.zio"   %% "zio-http"         % zioHttpV
  val zioHttpTestKit = "dev.zio"   %% "zio-http-testkit" % zioHttpV % Test

  val zioTest         = "dev.zio" %% "zio-test"          % zioV % Test
  val zioTestSbt      = "dev.zio" %% "zio-test-sbt"      % zioV % Test
  val zioTestMagnolia = "dev.zio" %% "zio-test-magnolia" % zioV % Test

  val stubby = "io.github.kitlangton" %% "stubby" % stubbyV % Test
}
