package com.matthewjones372.data.sql

import org.flywaydb.core.Flyway
import zio.*

import javax.sql.DataSource

object SwMigrations:
  def migrate: RIO[DataSource, Int] =
    for
      dataSource <- ZIO.service[DataSource]
      applied    <- ZIO.attemptBlocking {
                   Flyway
                     .configure()
                     .dataSource(dataSource)
                     .locations("classpath:db/migration")
                     .load()
                     .migrate()
                     .migrationsExecuted
                 }
      _ <- ZIO.logInfo(s"Applied $applied database migrations")
    yield applied
