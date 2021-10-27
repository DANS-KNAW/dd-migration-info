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

import nl.knaw.dans.lib.dataverse.model.dataset.DatasetVersion
import nl.knaw.dans.lib.dataverse.model.file.prestaged.{ Checksum, PrestagedFile }
import nl.knaw.dans.lib.dataverse.model.search.{ DatasetResultItem, SearchResult }
import nl.knaw.dans.lib.dataverse.{ DataverseInstance, DataverseResponse }
import nl.knaw.dans.lib.error._
import nl.knaw.dans.lib.logging.DebugEnhancedLogging
import resource.managed

import java.sql.{ Connection, SQLException }
import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.util.{ Failure, Try }

class DdMigrationInfoApp(configuration: Configuration) extends DebugEnhancedLogging {
  val database = new Database(
    url = configuration.databaseUrl,
    user = configuration.databaseUser,
    password = configuration.databasePassword,
    driver = configuration.databaseDriver)
  val dataverse = new DataverseInstance(configuration.dataverse)

  logger.info("Initializing database connection...")
  database.initConnectionPool()
  logger.info("Database connection initialized.")

  def loadBasicFileMetasForDataverse(): Try[Unit] = {
    trace(())
    for {
      dois <- getDoisOfPublishedDatasets
      _ <- dois.map(loadBasicFileMetasForDataset).collectResults
    } yield ()
  }

  def loadBasicFileMetasForDataset(datasetDoi: String): Try[Unit] = {
    trace(datasetDoi)
    for {
      r <- dataverse.dataset(datasetDoi).viewAllVersions()
      vs <- r.data
      bfms <- collectBasicFileMetas(vs)
      _ <- createBasicFileMetasForDataset(datasetDoi, bfms)
    } yield ()
  }

  private def getDoisOfPublishedDatasets: Try[List[String]] = {
    val dois = ListBuffer[String]()
    var maybeNextDois: Try[List[String]] = null
    var start = 0
    val perPage = 100
    do {
      maybeNextDois = getDoisFromSearchResult(dataverse.search().find("publicationStatus:\"Published\"", start = start, perPage = perPage))
      logger.info(s"Retrieved results page with offset $start")
      debug(s"maybeNextDois = $maybeNextDois")
      maybeNextDois.foreach(dois.appendAll)
      start += perPage
    } while (maybeNextDois.map(_.nonEmpty).getOrElse(false))
    if (maybeNextDois.isFailure) maybeNextDois
    else Try { dois.toList }
  }

  private def getDoisFromSearchResult(maybeSearchResponse: Try[DataverseResponse[SearchResult]]): Try[List[String]] = {
    for {
      r <- maybeSearchResponse
      searchResult <- r.data
      dois = searchResult.items.map(_.asInstanceOf[DatasetResultItem]).map(_.globalId)
    } yield dois
  }

  private def collectBasicFileMetas(datasetVersions: List[DatasetVersion]): Try[List[BasicFileMeta]] = Try {
    trace(datasetVersions)
    datasetVersions.filter(_.versionState.exists(_ == "RELEASED"))
      .sortBy(d => (d.versionNumber, d.versionMinorNumber))
      .zipWithIndex.flatMap {
      case (datasetVersion: DatasetVersion, index: Int) =>
        datasetVersion.files.map(f =>
          BasicFileMeta(
            label = f.label.get,
            directoryLabel = f.directoryLabel,
            versionSequenceNumber = index + 1,
            prestagedFile = f.toPrestaged)
        )
    }
  }

  private def createBasicFileMetasForDataset(datasetDoi: String, basicFileMetas: List[BasicFileMeta]): Try[Unit] = {
    trace(datasetDoi, basicFileMetas)
    basicFileMetas.map {
      bfm => createBasicFileMetaRecord(datasetDoi, bfm)
    }.collectResults.map(_ => ())
  }

  def createBasicFileMetaRecord(datasetDoi: String, basicFileMeta: BasicFileMeta): Try[Unit] = {
    database.doTransaction(implicit c => writeBasicFileMetaRecord(datasetDoi, basicFileMeta))
  }

  private def writeBasicFileMetaRecord(datasetDoi: String, basicFileMeta: BasicFileMeta)(implicit c: Connection): Try[Unit] = {
    trace(datasetDoi, basicFileMeta)

    val query =
      """
        |INSERT INTO basic_file_metadata (
        |  storage_identifier,
        |  dataset_doi,
        |  version_sequence_number,
        |  file_name,
        |  directory_label,
        |  mime_type,
        |  sha1_checksum)
        |VALUES (?, ?, ?, ?, ?, ?, ?);
        |""".stripMargin

    managed(c.prepareStatement(query))
      .map(prepStatement => {
        prepStatement.setString(1, basicFileMeta.prestagedFile.storageIdentifier)
        prepStatement.setString(2, datasetDoi)
        prepStatement.setInt(3, basicFileMeta.versionSequenceNumber)
        prepStatement.setString(4, basicFileMeta.label)
        prepStatement.setString(5, basicFileMeta.directoryLabel.orNull)
        prepStatement.setString(6, basicFileMeta.prestagedFile.mimeType)
        prepStatement.setString(7, basicFileMeta.prestagedFile.checksum.`@value`)
        prepStatement.executeUpdate()
      })
      .tried
      .map(_ => ())
      .recoverWith {
        case e: SQLException if e.getMessage.toLowerCase contains "unique constraint" =>
          Failure(BasicFileMetaAlreadyStoredException(basicFileMeta))
        case e => logger.error(s"Could not save basic file meta for doi =  $datasetDoi seqNr = ${basicFileMeta.versionSequenceNumber}", e)
          Failure(e)
      }
  }

  def getBasicFileMetasForDatasetVersion(datasetId: String, seqNr: Int): Try[List[BasicFileMeta]] = {
    database.doTransaction(implicit c => readBasicFileMetasForDatasetVersion(datasetId, seqNr))
  }

  private def readBasicFileMetasForDatasetVersion(datasetDoi: String, seqNr: Int)(implicit c: Connection): Try[List[BasicFileMeta]] = {
    trace(datasetDoi, seqNr)
    val query =
      """
        |SELECT storage_identifier,
        |       version_sequence_number,
        |       file_name,
        |       directory_label,
        |       mime_type,
        |       sha1_checksum
        |FROM basic_file_metadata
        |WHERE dataset_doi = ? AND version_sequence_number = ?;
        |""".stripMargin

    managed(c.prepareStatement(query))
      .map(prepStatement => {
        prepStatement.setString(1, datasetDoi)
        prepStatement.setInt(2, seqNr)
        prepStatement.executeQuery()
      })
      .map(r => {
        val dataFiles = new mutable.ListBuffer[BasicFileMeta]()

        while (r.next()) {
          dataFiles.append(
            BasicFileMeta(
              label = r.getString("file_name"),
              directoryLabel = Option(r.getString("directory_label")),
              versionSequenceNumber = r.getInt("version_sequence_number"),
              prestagedFile = PrestagedFile(
                storageIdentifier = r.getString("storage_identifier"),
                fileName = r.getString("file_name"),
                mimeType = r.getString("mime_type"),
                checksum = Checksum(
                  `@type` = "SHA-1",
                  `@value` = r.getString("sha1_checksum")
                )
              )))
        }
        dataFiles.toList
      }).tried
  }
}
