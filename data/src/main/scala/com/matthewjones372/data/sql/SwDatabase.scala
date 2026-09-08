package com.matthewjones372.data.sql

import org.sqlite.{SQLiteConfig, SQLiteDataSource}

import java.nio.file.Path
import javax.sql.DataSource

object SwDatabase:
  // Foreign keys are off by default in SQLite, so the cascades in the schema need asking for.
  private def configured(url: String): DataSource =
    val config = SQLiteConfig()
    config.enforceForeignKeys(true)
    val dataSource = SQLiteDataSource(config)
    dataSource.setUrl(url)
    dataSource

  def file(path: Path): DataSource = configured(s"jdbc:sqlite:${path.toAbsolutePath}")
