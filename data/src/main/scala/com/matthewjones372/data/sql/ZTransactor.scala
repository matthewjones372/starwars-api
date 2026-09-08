package com.matthewjones372.data.sql

import com.augustnagro.magnum
import com.augustnagro.magnum.{DbCon, DbTx, SqlLogger, Transactor}
import zio.*

import javax.sql.DataSource

// Magnum's own ZIO module is only published as a milestone, and this is all of it we need.
final class ZTransactor private (private val underlying: Transactor):
  def connect[A](query: DbCon ?=> A): Task[A] =
    ZIO.attemptBlocking(magnum.connect(underlying)(query))

  def transact[A](query: DbTx ?=> A): Task[A] =
    ZIO.attemptBlocking(magnum.transact(underlying)(query))

object ZTransactor:
  def apply(dataSource: DataSource, sqlLogger: SqlLogger = SqlLogger.Default): ZTransactor =
    new ZTransactor(Transactor(dataSource = dataSource, sqlLogger = sqlLogger))

  val layer: URLayer[DataSource, ZTransactor] =
    ZLayer.fromFunction((dataSource: DataSource) => ZTransactor(dataSource))
