package io.getquill.source.jdbc

import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import scala.util.DynamicVariable
import scala.util.control.NonFatal
import com.typesafe.scalalogging.StrictLogging
import io.getquill.source.sql.SqlSource
import scala.util.Try

class JdbcSource
    extends SqlSource[ResultSet, PreparedStatement]
    with JdbcEncoders
    with JdbcDecoders
    with StrictLogging {

  protected lazy val dataSource = DataSource(config)

  override lazy val dialect = DialectFromConfig(config)

  private val currentConnection = new DynamicVariable[Option[Connection]](None)

  protected def withConnection[T](f: Connection => T) =
    currentConnection.value.map(f).getOrElse {
      val conn = dataSource.getConnection
      try f(conn)
      finally conn.close
    }

  def transaction[T](f: => T) =
    withConnection { conn =>
      currentConnection.withValue(Some(conn)) {
        conn.setAutoCommit(false)
        try {
          val res = f
          conn.commit
          res
        } catch {
          case NonFatal(e) =>
            conn.rollback
            throw e
        } finally {
          conn.setAutoCommit(true)
        }
      }
    }

  def execute(sql: String) = {
    logger.info(sql)
    withConnection {
      _.prepareStatement(sql).executeUpdate
    }
  }

  def execute(sql: String, bindList: List[PreparedStatement => PreparedStatement]) = {
    logger.info(sql)
    withConnection { conn =>
      val ps = conn.prepareStatement(sql)
      for (bind <- bindList) {
        bind(ps)
        ps.addBatch
      }
      ps.executeBatch
    }
  }

  def query[T](sql: String, bind: PreparedStatement => PreparedStatement, extractor: ResultSet => T) = {
    logger.info(sql)
    withConnection { conn =>
      val ps = bind(conn.prepareStatement(sql))
      val rs = ps.executeQuery
      extractResult(rs, extractor)
    }
  }

  private def extractResult[T](rs: ResultSet, extractor: ResultSet => T): List[T] =
    if (rs.next)
      extractor(rs) +: extractResult(rs, extractor)
    else
      List()
}
