import sbt.*
import sbt.Keys.*

object GenerateOpenApiTask extends AutoPlugin {
  override def trigger = noTrigger

  object autoImport {
    val generateOpenAPIDocs = taskKey[Unit]("Generates OpenAPI documentation")
  }

  import autoImport.*

  override lazy val projectSettings: Seq[Setting[?]] = Seq(
    generateOpenAPIDocs := (Compile / runMain).toTask(" GenerateOpenApiDocs").value
  )
}
