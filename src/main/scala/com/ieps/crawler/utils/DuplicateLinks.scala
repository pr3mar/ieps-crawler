package com.ieps.crawler.utils

import com.ieps.crawler.db.DBService
import com.ieps.crawler.db.Tables.{ImageRow, PageRow, PageDataRow}
import com.typesafe.scalalogging.StrictLogging
import slick.jdbc.PostgresProfile.api.Database

class DuplicateLinks(db: Database) extends StrictLogging {
  private val dbService = new DBService(db)

  def isDuplicatePage(pageRow: PageRow): Boolean = {
    dbService.pageExists(pageRow)
  }

  def isDuplicateImage(imageRow: ImageRow): Boolean = {
    dbService.imageExists(imageRow)
  }
  def isDuplicatePageData(pageDataRow: PageDataRow): Boolean = {
    dbService.pageDataExists(pageDataRow)
  }

  def deduplicatePages(pageRows: List[PageRow]): List[PageRow] = {
    val duplicateUrls: Set[String] = duplicatePages(pageRows).filter(_.url.nonEmpty).map(_.url.get).toSet
    pageRows.filter(_.url.nonEmpty).filter(page => !duplicateUrls.contains(page.url.get))
  }

  def duplicatePages(pageRows: List[PageRow]): Seq[PageRow] = {
    pageRows.zip(dbService.pageExists(pageRows)).filter(_._2).map(_._1)
  }
}
