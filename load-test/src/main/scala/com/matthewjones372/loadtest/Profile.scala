package com.matthewjones372.loadtest

import zio.Task
import zio.ZIO
import java.nio.file.Path
import jdk.jfr.consumer.RecordingFile
import scala.jdk.CollectionConverters.*

object Profile:

  def hottest(recording: Path, take: Int): Task[List[String]] =
    ZIO.attemptBlocking:
      val samples = RecordingFile
        .readAllEvents(recording)
        .asScala
        .filter(_.getEventType.getName == "jdk.ExecutionSample")
        .flatMap(event => Option(event.getStackTrace))
        .flatMap(_.getFrames.asScala.headOption)
        .map(frame => s"${frame.getMethod.getType.getName}.${frame.getMethod.getName}")
      samples
        .groupBy(identity)
        .view
        .mapValues(_.size)
        .toList
        .sortBy(-_._2)
        .take(take)
        .map((method, count) => f"$count%6d  $method")
