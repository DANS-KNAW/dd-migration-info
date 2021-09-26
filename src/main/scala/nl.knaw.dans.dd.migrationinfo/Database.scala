/*
 * Copyright (C) 2021 DANS - Data Archiving and Networked Services (info@dans.knaw.nl)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package nl.knaw.dans.dd.migrationinfo

import nl.knaw.dans.lib.error.TryExtensions
import nl.knaw.dans.lib.logging.DebugEnhancedLogging
import org.apache.commons.dbcp2.BasicDataSource
import resource.managed

import java.net.URI
import java.sql.Connection
import javax.sql.DataSource
import scala.util.control.NonFatal
import scala.util.{ Failure, Try }

class Database(url: URI, user: String, password: String, driver: String) extends DebugEnhancedLogging {
  type ConnectionPool = DataSource with AutoCloseable

  private var pool: ConnectionPool = _

  protected def createConnectionPool: ConnectionPool = {
    val source = new BasicDataSource
    source.setDriverClassName(driver)
    source.setUrl(url.toASCIIString)
    source.setUsername(user)
    source.setPassword(password)
    source
  }

  def initConnectionPool(): Try[Unit] = Try {
    logger.info("Creating database connection ...")
    pool = createConnectionPool
    logger.info(s"Database connected with URL = ${url.toASCIIString}, user = $user, password = ****")
  }

  def closeConnectionPool(): Try[Unit] = Try {
    logger.info("Closing database connection ...")
    pool.close()
    logger.info("Database connection closed")
  }

  /**
   * Performs a database transaction with the function argument. Given the `Connection` in
   * `actionFunc`, the user can create and execute SQL statements and queries, which must result in
   * a `Try[T]`. If the result of `actionFunc` is a `Success`, the transaction will be completed by
   * committing the results to the database. If the result of `actionFunc` is a `Failure`,
   * the changes made during this transaction are rolled back.
   * The result (either `Success` or `Failure`) will then be returned.
   *
   * '''Note:''' the user is not supposed to close the connection, to commit or rollback any changes
   * in `actionFunc` itself.
   *
   * @param actionFunc the actions to be performed on the database given the `Connection`
   * @tparam T the return type of the performed actions
   * @return `Success` if the actions performed on the database were successful; `Failure` otherwise
   */
  def doTransaction[T](actionFunc: Connection => Try[T]): Try[T] = {
    managed(pool.getConnection)
      .map(connection => {
        connection.setAutoCommit(false)
        val savepoint = connection.setSavepoint()

        actionFunc(connection)
          .doIfSuccess(_ => {
            connection.commit()
            connection.setAutoCommit(true)
          })
          .recoverWith {
            case NonFatal(e) => Try { connection.rollback(savepoint) }.flatMap(_ => Failure(e))
          }
      })
      .tried
      .flatten
  }
}
