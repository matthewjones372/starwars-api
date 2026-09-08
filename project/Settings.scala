import sbt.*
import sbt.Keys.*

object Projects {
  def create(name: String): Project = create(name, name)

  def create(name: String, fileName: String): Project =
    Project(name, base = file(fileName))
      .settings(
        Compile / javacOptions ++= Seq("-release", "25"),
        scalacOptions ++= Seq("-java-output-version", "25")
      )
      .settings(
        Test / fork := true,
        run / fork  := true
      )
}
