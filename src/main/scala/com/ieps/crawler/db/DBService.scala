package com.ieps.crawler.db

import com.ieps.crawler.db
import slick.jdbc.PostgresProfile.api._

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.util.{Failure, Success}

class DBService(db: Database) {
  import Tables._

  implicit val timeout: FiniteDuration = new FiniteDuration(1, MINUTES)

  // Site
  def getSiteByIdFuture(id: Int): Future[Option[SiteRow]] =
    db.run(CrawlerDIO.findSiteByIdOption(id))

  def getSiteById(id: Int): Option[SiteRow] =
    Await.result(getSiteByIdFuture(id), timeout)

  def getSiteByDomainFuture(domain: String): Future[Option[SiteRow]] =
    db.run(CrawlerDIO.findPageByDomainOption(domain))

  def getSiteByDomain(domain: String): Option[SiteRow] =
    Await.result(getSiteByDomainFuture(domain), timeout)

  def insertSiteFuture(site: SiteRow): Future[SiteRow] =
    db.run(CrawlerDIO.insertSite(site))

  def insertSite(site: SiteRow): SiteRow =
    Await.result(insertSiteFuture(site), timeout)

  def insertOrUpdateSiteFuture(site: SiteRow): Future[SiteRow] =
    db.run(CrawlerDIO.insertOrUpdateSite(site))

  def insertOrUpdateSite(site: SiteRow): SiteRow =
    Await.result(insertOrUpdateSiteFuture(site), timeout)

  def insertIfNotExistsByDomainFuture(siteRow: SiteRow): Future[SiteRow] =
    db.run(CrawlerDIO.insertIfNotExistsByDomain(siteRow))

  def insertIfNotExistsByDomain(siteRow: SiteRow): SiteRow =
    Await.result(insertIfNotExistsByDomainFuture(siteRow), timeout)

  // Page
  def getPageByIdFuture(id: Int): Future[PageRow] =
    db.run(CrawlerDIO.findPageById(id))

  def getPageById(id: Int): PageRow =
    Await.result(getPageByIdFuture(id), timeout)

  def insertPageFuture(page: PageRow): Future[PageRow] =
    db.run(CrawlerDIO.insertPage(page))

  def insertPage(page: PageRow): PageRow =
    Await.result(insertPageFuture(page), timeout)

  def insertPageFuture(pages: List[PageRow]): Future[Seq[PageRow]] =
    db.run(CrawlerDIO.insertPage(pages))

  def insertPage(pages: List[PageRow]): Seq[PageRow] =
    Await.result(insertPageFuture(pages), timeout)

  def insertOrUpdatePageFuture(pages: List[PageRow]): Future[Seq[PageRow]] =
    db.run(CrawlerDIO.insertOrUpdatePage(pages))

  def insertOrUpdatePage(pages: List[PageRow]): Seq[PageRow] =
    Await.result(insertOrUpdatePageFuture(pages), timeout)

  def insertOrUpdatePage(page: PageRow): PageRow =
    insertOrUpdatePage(List(page)).head

  def insertIfNotExistsFuture(page: PageRow): Future[PageRow] =
    db.run(CrawlerDIO.insertIfNotExists(page))

  def insertIfNotExists(page: PageRow): PageRow =
    Await.result(insertIfNotExistsFuture(page), timeout)

  // bulk insert
  def insertSiteWithPageFuture(site: SiteRow, page: PageRow): Future[(SiteRow, PageRow)] =
    db.run(CrawlerDIO.insertSiteWithPage(site, page))

  def insertSiteWithPage(site: SiteRow, page: PageRow): (SiteRow, PageRow) =
    Await.result(insertSiteWithPageFuture(site, page), timeout)

  def insertSiteWithPagesFuture(site: SiteRow, page: Seq[PageRow]): Future[(SiteRow, Seq[PageRow])] =
    db.run(CrawlerDIO.insertSiteWithPages(site, page))

  def insertSiteWithPages(site: SiteRow, page: Seq[PageRow]): (SiteRow, Seq[PageRow]) =
    Await.result(insertSiteWithPagesFuture(site, page), timeout)

  def insertPageWithContentFuture(page: PageRow, images: Seq[ImageRow], pageDatum: Seq[PageDataRow], pageLinks: Seq[PageRow]): Future[(PageRow, Seq[ImageRow], Seq[PageDataRow], Seq[PageRow])] =
    db.run(CrawlerDIO.insertPageWithContent(page, images, pageDatum, pageLinks))

  def insertPageWithContent(page: PageRow, images: Seq[ImageRow], pageDatum: Seq[PageDataRow], pageLinks: Seq[PageRow]): (PageRow, Seq[ImageRow], Seq[PageDataRow], Seq[PageRow]) =
    Await.result(insertPageWithContentFuture(page, images, pageDatum, pageLinks), timeout)

  def pageExistsByUrlFuture(page: PageRow): Future[Boolean] =
    db.run(CrawlerDIO.pageExistsByUrl(page))

  def pageExistsByUrl(page: PageRow): Boolean =
    Await.result(pageExistsByUrlFuture(page), timeout)

  def pageExistsByUrlFuture(page: List[PageRow]): Future[Seq[PageRow]] =
    db.run(CrawlerDIO.pageExistsByUrl(page))

  def pageExistsByUrl(page: List[PageRow]): Seq[PageRow] =
    Await.result(pageExistsByUrlFuture(page), timeout)

  def pageExistsByHashFuture(page: PageRow): Future[Boolean] =
    db.run(CrawlerDIO.pageExistsByHash(page))

  def pageExistsByHash(page: PageRow): Boolean =
    Await.result(pageExistsByHashFuture(page), timeout)

  def pageExistsFuture(page: PageRow): Future[Boolean] =
    db.run(CrawlerDIO.pageExists(page))

  def pageExists(page: PageRow): Boolean =
    Await.result(pageExistsFuture(page), timeout)

  def pageExistsFuture(page: List[PageRow]): Future[List[Boolean]] =
    db.run(CrawlerDIO.pageExists(page))

  def pageExists(page: List[PageRow]): List[Boolean] =
    Await.result(pageExistsFuture(page), timeout)

  def pageExistsByHashFuture(page: List[PageRow]): Future[Seq[PageRow]] =
    db.run(CrawlerDIO.pageExistsByHash(page))

  def pageExistsByHash(page: List[PageRow]): Seq[PageRow] =
    Await.result(pageExistsByUrlFuture(page), timeout)

  def linkPagesFuture(fromPage: PageRow, toPage: PageRow): Future[LinkRow] =
    db.run(CrawlerDIO.linkPages(fromPage, toPage))

  def linkPagesFuture(fromPage: PageRow, toPages: List[PageRow]): Future[Seq[LinkRow]] =
    db.run(CrawlerDIO.insertLinkIfNotExists(fromPage, toPages))

  def linkPages(fromPage: PageRow, toPage: PageRow): LinkRow =
    Await.result(linkPagesFuture(fromPage, toPage), timeout)

  def linkPages(fromPage: PageRow, toPages: List[PageRow]): Seq[LinkRow] =
    Await.result(linkPagesFuture(fromPage, toPages), timeout)

  def linkPages(fromPage: PageRow, toPages: Seq[PageRow]): Seq[LinkRow] =
    Await.result(linkPagesFuture(fromPage, toPages.toList), timeout)

  def linkPages(fromPage: Option[PageRow], toPage: PageRow): Option[LinkRow] =
    fromPage.map(fromPage => linkPages(fromPage, toPage))

  // bulk read
  def getPageLinksFuture(id: Int): Future[(PageRow, Seq[PageRow])] =
    db.run(CrawlerDIO.getPageLinksById(id))

  def findPagesByLinkTarget(links: Seq[LinkRow]): Future[Seq[PageRow]] =
    db.run(CrawlerDIO.findPageByLinkTarget(links))

  def getPageLinks(id: Int): Seq[PageRow] =
    Await.result(getPageLinksFuture(id), timeout)._2

  def getPageLinks(page: PageRow): Seq[PageRow] =
    getPageLinks(page.id)

  def getPageContentFuture(id: Int): Future[(Seq[ImageRow], Seq[PageDataRow])] =
    db.run(CrawlerDIO.getPageContents(id))

  def getPageContent(id: Int): (Seq[ImageRow], Seq[PageDataRow]) =
    Await.result(getPageContentFuture(id), timeout)

  def getPageContent(page: PageRow): (Seq[ImageRow], Seq[PageDataRow]) =
    Await.result(getPageContentFuture(page.id), timeout)

  def insertImageIfNotExistsFuture(imageRow: ImageRow): Future[ImageRow] =
    db.run(CrawlerDIO.insertImage(imageRow))

  def insertImageIfNotExists(imageRow: ImageRow): ImageRow =
    Await.result(insertImageIfNotExistsFuture(imageRow), timeout)

  def insertPageDataIfNotExistsFuture(pageDataRow: PageDataRow): Future[PageDataRow] =
    db.run(CrawlerDIO.insertPageData(pageDataRow))

  def insertPageDataIfNotExists(pageDataRow: PageDataRow): PageDataRow =
    Await.result(insertPageDataIfNotExistsFuture(pageDataRow), timeout)

  def imageExistsFuture(imageRow: ImageRow): Future[Boolean] =
    db.run(CrawlerDIO.imageExists(imageRow))

  def imageExists(imageRow: ImageRow): Boolean =
    Await.result(imageExistsFuture(imageRow), timeout)


  def pageDataExistsFuture(pageDataRow: PageDataRow): Future[Boolean] =
    db.run(CrawlerDIO.pageDataExists(pageDataRow))

  def pageDataExists(pageDataRow: PageDataRow): Boolean =
    Await.result(pageDataExistsFuture(pageDataRow), timeout)

}
