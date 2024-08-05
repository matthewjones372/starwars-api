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

  lazy val zioJson = Seq(
    libraryDependencies ++= Seq(
      Dependencies.zioJson
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

  lazy val resilience = Seq(
    libraryDependencies ++= Seq(
      Dependencies.resilience
    )
  )
}
