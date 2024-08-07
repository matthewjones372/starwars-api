import sbt._
import sbt.Keys._

object GenerateOpenApiTask extends AutoPlugin {
  override def trigger = allRequirements

  object autoImport {
    val generateOpenAPIDocs = taskKey[Unit]("Generates OpenAPI documentation")
  }

  import autoImport._

  override lazy val projectSettings = Seq(
    Compile / unmanagedSourceDirectories += baseDirectory.value / "src" / "main" / "scripts",
    generateOpenAPIDocs := {
      val log          = streams.value.log
      val cp           = (Compile / fullClasspath).value
      val scalaOptions = Seq("-cp", cp.files.mkString(":"))
      val runOptions   = Seq("GenerateOpenApiDocs")

      val result = new ForkRun(ForkOptions())
        .run("GenerateOpenApiDocs", cp.files, runOptions, log)

      log.info(s"OpenAPI documentation generation result: $result")
    }
  )
}
