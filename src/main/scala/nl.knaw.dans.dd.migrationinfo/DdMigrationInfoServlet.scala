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

import nl.knaw.dans.lib.dataverse.DataverseException
import nl.knaw.dans.lib.error._
import nl.knaw.dans.lib.logging.DebugEnhancedLogging
import org.json4s.native.Serialization
import org.json4s.{ DefaultFormats, Formats }
import org.scalatra._

import scala.util.control.NonFatal

class DdMigrationInfoServlet(app: DdMigrationInfoApp,
                             version: String) extends ScalatraServlet with DebugEnhancedLogging {
  implicit val jsonFormats: Formats = DefaultFormats

  get("/") {
    contentType = "text/plain"
    Ok(s"Migration Info Service running ($version)")
  }

  private def getDatasetId: String = {
    if (params("id") == ":persistentId") params("persistentId")
    else throw new UnsupportedOperationException("Only :persistendId type identifiers are currently supported") // TODO: convert to getDatasetDoi. If a database ID, the DOI must be looked up in Dataverse
  }

  post("/datasets/:id/actions/load-from-dataverse") {
    val datasetId = getDatasetId
    app.loadBasicFileMetasForDataset(datasetId).map(_ => Ok(s"Records added for dataset $datasetId"))
      .doIfFailure {
        case NonFatal(e) => logger.warn(s"Exception when creating DataFile records for dataset $datasetId", e)
      }
      .recover {
        case e: DataverseException if e.status == 404 => NotFound(s"No such dataset: ${ datasetId }")
        case NonFatal(e) => InternalServerError(e.getMessage)
      }.get
  }

  post("/datasets/actions/load-from-dataverse") {
    app.loadBasicFileMetasForDataverse()
      .map(_ => Ok("Records added for dataverse root"))
      .doIfFailure {
        case NonFatal(e) => logger.warn("Errors when loading data file records from dataverse root", e)
      }
      .recover {
        case NonFatal(e) => InternalServerError(e.getMessage)
      }
      .get
  }

  get("/datasets/:id/seq/:seqNr/basic-file-metas") {
    val datasetId = getDatasetId
    val seqNr = params("seqNr").toInt
    debug(s"Getting basic-file-metas for dataset = ${ datasetId } seqNr = $seqNr")
    app.getBasicFileMetasForDatasetVersion(datasetId, seqNr)
      .map {
        dfs =>
          if (dfs.nonEmpty) Ok(Serialization.writePretty(dfs))
          else NotFound()
      }.doIfFailure {
      case NonFatal(e) => logger.error("Error retrieving BasicFileMetas for dataset", e)
    }.get
  }
}
