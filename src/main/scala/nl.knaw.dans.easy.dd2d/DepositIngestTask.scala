/**
 * Copyright (C) 2020 DANS - Data Archiving and Networked Services (info@dans.knaw.nl)
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
package nl.knaw.dans.easy.dd2d

import java.nio.charset.StandardCharsets

import better.files.File
import nl.knaw.dans.easy.dd2d.dansbag.DansBagValidator
import nl.knaw.dans.easy.dd2d.dataverse.DataverseInstance
import nl.knaw.dans.easy.dd2d.mapping.AccessRights
import nl.knaw.dans.easy.dd2d.queue.Task
import nl.knaw.dans.lib.error._
import nl.knaw.dans.lib.logging.DebugEnhancedLogging
import org.json4s.Formats
import org.json4s.native.JsonMethods._
import org.json4s.native.Serialization
import scalaj.http.HttpResponse

import scala.language.postfixOps
import scala.util.{ Success, Try }

/**
 * Checks one deposit and then ingests it into Dataverse.
 *
 * @param deposit     the deposit to ingest
 * @param dataverse   the Dataverse instance to ingest in
 * @param jsonFormats implicit necessary for pretty-printing JSON
 */
case class DepositIngestTask(deposit: Deposit, dansBagValidator: DansBagValidator, dataverse: DataverseInstance, publish: Boolean = true)(implicit jsonFormats: Formats) extends Task with DebugEnhancedLogging {
  trace(deposit, dataverse)

  private val ddmMapper = new DdmToDataverseMapper()
  private val bagDirPath = File(deposit.bagDir.path)
  private val filesXmlMapper = new FilesXmlToDataverseMapper(bagDirPath)

  override def run(): Try[Unit] = {
    trace(())
    logger.info(s"Ingesting $deposit into Dataverse")

    for {
      validationResult <- dansBagValidator.validateBag(bagDirPath)
      _ <- Try {
        if (!validationResult.isCompliant) throw RejectedDepositException(deposit,
          s"""
             |Bag was not valid according to Profile Version ${ validationResult.profileVersion }.
             |Violations:
             |${ validationResult.ruleViolations.map(_.map(formatViolation).mkString("\n")).getOrElse("") }
          """.stripMargin)
      }
      ddm <- deposit.tryDdm
      dataverseDataset <- ddmMapper.toDataverseDataset(ddm)
      json = Serialization.writePretty(dataverseDataset)
      _ = if (logger.underlying.isDebugEnabled) {
        debug(json)
      }
      response <- if (deposit.doi.nonEmpty) dataverse.dataverse("root").importDataset(json, isDdi = false, "doi:" + deposit.doi, keepOnDraft = true)
                  else dataverse.dataverse("root").createDataset(json)
      datasetId <- readIdFromResponse(response)
      _ <- uploadFilesToDataset(datasetId)
      _ <- if (publish) {
        debug("Publishing dataset")
        publishDataset(datasetId)
      }
           else {
             debug("Keeping dataset on DRAFT")
             Success(())
           }
    } yield ()
    // TODO: delete draft if something went wrong
  }

  private def formatViolation(v: (String, String)): String = v match {
    case (nr, msg) => s" - [$nr] $msg"
  }

  private def uploadFilesToDataset(datasetId: String): Try[Unit] = {
    trace(datasetId)
    for {
      filesXml <- deposit.tryFilesXml
      ddm <- deposit.tryDdm
      defaultRestrict <- Try { (ddm \ "profile" \ "accessRights").headOption.forall(AccessRights toDefaultRestrict) }
      files <- filesXmlMapper.toDataverseFiles(filesXml, defaultRestrict)
      _ <- files.map(f => dataverse.dataset(datasetId, isPersistentId = true).addFile(f.file, Option.empty[File], Some(Serialization.writePretty(f.metadata)))).collectResults
    } yield ()
  }

  private def readIdFromResponse(response: HttpResponse[Array[Byte]]): Try[String] = Try {
    trace(())
    val responseBodyAsString = new String(response.body, StandardCharsets.UTF_8)
    (parse(responseBodyAsString) \\ "persistentId")
      .extract[String]
  }

  private def publishDataset(datasetId: String): Try[Unit] = {
    dataverse.dataset(datasetId, isPersistentId = true).publish("major").map(_ => ())
  }
}