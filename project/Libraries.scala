import sbt._
import Keys._

object Libraries {
  lazy val zio = Seq(
    libraryDependencies ++= Seq(
      Dependencies.zio,
      Dependencies.zioConcurrent
    )
  )

  lazy val zioTest = Seq(
    libraryDependencies ++= Seq(
      Dependencies.zioTest,
      Dependencies.zioTestSbt,
      Dependencies.zioTestMagnolia,
      Dependencies.stubby
    )
  )

  lazy val zioHttp = Seq(
    libraryDependencies ++= Seq(
      Dependencies.zioHttp,
      Dependencies.zioHttpTestKit
    )
  )

  lazy val zioSchema = Seq(
    libraryDependencies ++= Seq(
      Dependencies.zioSchemaJson,
      Dependencies.zioSchema
    )
  )

  lazy val zioCache = Seq(
    libraryDependencies ++= Seq(
      Dependencies.zioCache
    )
  )

  lazy val zioConfig = Seq(
    libraryDependencies ++= Seq(
      Dependencies.zioConfig,
      Dependencies.zioConfigMagnolia,
      Dependencies.zioConfigTypeSafe
    )
  )

  lazy val zioLogging = Seq(
    libraryDependencies ++= Seq(
      Dependencies.zioLoggingSl4j,
      Dependencies.sl4jApi,
      Dependencies.sl4jSimple
    )
  )

  lazy val resilience = Seq(
    libraryDependencies ++= Seq(
      Dependencies.resilience
    )
  )
}
