package com.ieps.crawler.actors

import akka.actor.{Actor, PoisonPill, Props}
import akka.event.LoggingReceive
import com.ieps.crawler.db.Tables.{ImageRow, PageDataRow, PageRow, SiteRow}
import com.ieps.crawler.db.{DBService, Tables}
import com.ieps.crawler.queue.PageQueue
import com.ieps.crawler.queue.Queue.QueuePageEntry
import com.ieps.crawler.utils.HeadlessBrowser.FailedAttempt
import com.ieps.crawler.utils._
import com.typesafe.scalalogging.StrictLogging
import org.joda.time.{DateTime, DateTimeZone}
import slick.jdbc.PostgresProfile.api.Database

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext}
import scala.language.postfixOps
import scala.util.{Failure, Success}

object DomainWorkerActor {

  def props(workerId: String, db: Database) = Props(new DomainWorkerActor(workerId, db))

  // to be received from the FrontierManager
  case class ProcessDomain(siteRow: SiteRow, initialUrls: List[QueuePageEntry], download: Boolean = false) // !! has to be previously persisted to disk
  case class AddLinksToLocalQueue(links: List[QueuePageEntry])
  case object ProcessNextPage // self-sending the next message
  case object StatusReport
  case object StatusRequest
  case class StatusResponse(currentSite: Option[SiteRow], isWaiting: Boolean, queueSize: Long)
}

class DomainWorkerActor(
    val workerId: String,
    val db: Database
  ) extends Actor
    with StrictLogging {
  import DomainWorkerActor._
  import FrontierManagerActor._

  private implicit val executionContext: ExecutionContext = context.system.dispatchers.lookup("thread-pool-dispatcher")
  private implicit val timeout: FiniteDuration = 30 seconds
  private implicit val delay: FiniteDuration = 4 seconds
  private var logInstanceIdentifier: String = s"Worker_$workerId:"
  private var isWaiting: Boolean = false
  private var downloadData: Boolean = false
  private val dbService = new DBService(db)
  private val duplicate = new DuplicateLinks(db)
  private val browser = new HeadlessBrowser()
  private val queue: PageQueue = new PageQueue("queue/", workerId, true)

  context.system.scheduler.schedule(15 seconds, 10 seconds, self, StatusReport)


  // mutable state, keep as simple as possible
  private var currentSite: Option[SiteRobotsTxt]= None

  override def receive: Receive = LoggingReceive {
    case PoisonPill =>
      queue.close()
      context.stop(self)

    case ProcessDomain(site, initialUrls, download) =>
      if(site.domain.isEmpty) {
        logger.error(s"$logInstanceIdentifier undefined domain")
        context.parent ! NewDomainRequest
      } else {
        logger.info(s"$logInstanceIdentifier got a new domain: ${site.domain.get}")
        logInstanceIdentifier = s"Worker_$workerId (${site.domain.get}):"
        currentSite = Some(new SiteRobotsTxt(site))
        queue.enqueueAll(List(QueuePageEntry(PageRow(
          id = -1,
          siteId = Some(site.id),
          url = Canonical.getCanonical(site.domain.get),
          storedTime = Some(DateTime.now(DateTimeZone.UTC))
        ))) ++ initialUrls)
        downloadData = download
        self ! ProcessNextPage
      }

    case StatusRequest =>
      val site = currentSite.map(_.site)
      sender ! StatusResponse(site, isWaiting, queue.size())

    case StatusReport =>
      currentSite.map(_.site) match {
        case Some(site) =>
          logger.info(s"$logInstanceIdentifier Status report: domain: ${site.domain.get}, waiting: $isWaiting, download: $downloadData, queue size: ${queue.size()}")
          if (queue.isEmpty) {
            logger.info(s"$logInstanceIdentifier Requesting new domain")
            context.parent ! NewDomainRequest(site)
          }
        case None =>
          logger.info(s"$logInstanceIdentifier Status report: domain: none, waiting: $isWaiting, download: $downloadData, queue size: ${queue.size()}")
          context.parent ! NewDomainRequest
      }


    case AddLinksToLocalQueue(links) =>
      queue.enqueueAll(links)

    case  ProcessNextPage =>
      isWaiting = false
        if (!queue.hasMorePages) {
          logger.info(s"$logInstanceIdentifier queue is empty.")
        } else {
          queue.dequeue().map(processPage).foreach {
            case List() =>
            case masterQueueEntries => context.parent ! AddLinksToFrontier(masterQueueEntries)
          }
        }
  }

  def processPage(queueEntry: QueuePageEntry): List[QueuePageEntry] = {
    if (!currentSite.get.isAllowed(queueEntry.pageInQueue)) {
      // handle disallowed pages
      handleDisallowed(queueEntry)
    } else if (isWaiting) {
      return List.empty
    } else {
      // handle original pages
      queueEntry.dataType match {
        case 0 => // html
          if (duplicate.isDuplicatePage(queueEntry.pageInQueue)) {
            // handle duplicate pages
            handleDuplicate(queueEntry)
          } else {
            val masterQueueEntries = handleAllowed(queueEntry)
            context.system.scheduler.scheduleOnce(currentSite.get.getDelay millis, self, ProcessNextPage)
            isWaiting = true
            return masterQueueEntries
          }
        case 1 => // images
          handleImage(queueEntry)
          if (downloadData) {
            context.system.scheduler.scheduleOnce(currentSite.get.getDelay millis, self, ProcessNextPage)
            isWaiting = true
            return List.empty
          }
        case 2 => // data
          handlePageData(queueEntry)
          if (downloadData) {
            context.system.scheduler.scheduleOnce(currentSite.get.getDelay millis, self, ProcessNextPage)
            isWaiting = true
            return List.empty
          }
      }
    }
    self ! ProcessNextPage
    List.empty
  }

  def handleDisallowed(queuePageEntry: QueuePageEntry): Unit = {
    logger.debug(s"$logInstanceIdentifier Page disallowed: ${queuePageEntry.pageInQueue.url.get}")
    storeAndLinkPage(
      queuePageEntry.pageInQueue.copy(
        siteId = Some(currentSite.get.site.id),
        pageTypeCode = Some("DISALLOWED"),
        storedTime = Some(DateTime.now(DateTimeZone.UTC))
      ),
      queuePageEntry
    )
  }

  def handleDuplicate(queuePageEntry: QueuePageEntry): Unit = {
    logger.debug(s"$logInstanceIdentifier Page is duplicate: ${queuePageEntry.pageInQueue.url.get}")
    storeAndLinkPage(
      queuePageEntry.pageInQueue,
      queuePageEntry
    )
  }

  def handleAllowed(queuePageEntry: QueuePageEntry): List[QueuePageEntry] = {
    val queuedPage = queuePageEntry.pageInQueue.copy(siteId = Some(currentSite.get.site.id))
    logger.info(s"$logInstanceIdentifier working on ${queuedPage.url.get}")
    Await.ready(browser.getPageSource(queuedPage), timeout).value.get match {
      case Success(pageWithContent: PageRow) =>
        val insertedPage = storeAndLinkPage(pageWithContent, queuePageEntry)
        val httpStatusCode = insertedPage.httpStatusCode.get
        if (200 <= httpStatusCode && httpStatusCode < 400) {
          val extractor = new ExtractFromHTML(insertedPage, currentSite.get.site)
          // enqueue the page data
          extractor.getPageData.foreach( links => {
            val domainLinks = filterDomainPages(Some(links)).get
            duplicate.deduplicatePages(domainLinks).map(link => QueuePageEntry(link, 2, Some(insertedPage.copy(htmlContent = None)))).foreach(queue.enqueue)
          })

          // enqueue the images
          extractor.getImages.foreach( links => {
            val domainLinks = filterDomainPages(Some(links)).get
            duplicate.deduplicatePages(domainLinks).map(link => QueuePageEntry(link, 1, Some(insertedPage.copy(htmlContent = None)))).foreach(queue.enqueue)
          })

          // enqueue the extracted links
          val allLinks: Option[List[PageRow]] = extractor.getPageLinks
          allLinks.foreach( links => {
            dbService.linkPages(insertedPage, duplicate.duplicatePages(links))
            val domainLinks = filterDomainPages(Some(links)).get
            duplicate.deduplicatePages(domainLinks).map(link => QueuePageEntry(link, 0, Some(insertedPage.copy(htmlContent = None)))).foreach(queue.enqueue)
          })

          filterNonDomainPages(allLinks) match {
            case Some(links) =>
              duplicate.deduplicatePages(links).map(QueuePageEntry(_, 0, Some(insertedPage.copy(htmlContent = None))))
            case None => List.empty
          }
        } else {
//          logger.warn(s"$logInstanceIdentifier Got status code $httpStatusCode")
          List.empty
        }
      case Failure(exception: FailedAttempt) =>
//        logger.error(s"$logInstanceIdentifier Failed getting content: ${exception.getMessage}")
        storeAndLinkPage(exception.pageRow, queuePageEntry)
        List.empty
      case Failure(exception) =>
        logger.error(s"$logInstanceIdentifier Unknown error: ${exception.getMessage}")
        List.empty
    }
  }

  def handleImage(entry: QueuePageEntry): Unit = {
    val queuedImage = entry.pageInQueue
    entry.referencePage.foreach(referencePage => {
      logger.info(s"$logInstanceIdentifier [image] working on ${queuedImage.url.get}")
      val imageRow = ImageRow(
        id = -1,
        pageId = Some(referencePage.id),
        filename = queuedImage.url
      )
      if (!dbService.imageExists(imageRow)) {
        if(downloadData) {
          browser.getImageData(imageRow).map(imageWithData => {
            dbService.insertImageIfNotExists(imageWithData)
          })
        } else {
          dbService.insertImageIfNotExists(imageRow)
        }
      } else {
        dbService.insertImageIfNotExists(imageRow)
      }
    })
  }

  def handlePageData(entry: QueuePageEntry): Unit = {
    val queuedPageData = entry.pageInQueue
    entry.referencePage.foreach(referencePage => {
      logger.info(s"$logInstanceIdentifier [page data] working on ${queuedPageData.url.get}")
      val pageDataRow = PageDataRow(
        id = -1,
        pageId = Some(referencePage.id),
        filename = queuedPageData.url
      )
      if (!dbService.pageDataExists(pageDataRow)) {
        if (downloadData) {
          browser.getPageData(pageDataRow).map(pageData => {
            dbService.insertPageDataIfNotExists(pageData)
          })
        } else {
          dbService.insertPageDataIfNotExists(pageDataRow)
        }
      } else {
        dbService.insertPageDataIfNotExists(pageDataRow)
      }
    })
  }

  def storeAndLinkPage(pageRow: PageRow, queuePageEntry: QueuePageEntry): PageRow = {
    val insertedPage = dbService.insertIfNotExists(pageRow)
    dbService.linkPages(queuePageEntry.referencePage, insertedPage)
    insertedPage
  }

  def filterDomainPages(links: Option[List[PageRow]]): Option[List[Tables.PageRow]] = {
    links.map(_.filter(currentSite.get.pageBelongs(_)))
  }

  def filterNonDomainPages(links: Option[List[PageRow]]): Option[List[PageRow]] = {
    links.map(_.filter(!currentSite.get.pageBelongs(_)))
  }
}
