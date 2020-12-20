package code.restService

import net.liftweb.http.rest.RestHelper
import net.liftweb.http._
import net.liftweb.json.JsonDSL._
import code.lib.AppAux._
import code.model.Entities._
import code.restService.RestClient.{contributorsByRepo, reposByOrganization}

import scala.concurrent.{Await, Future}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Failure, Success, Try}
import java.time.{Duration, Instant}
import java.util.Date

object RestServer extends RestHelper {

  serve({
    case Req("org" :: organization :: "contributors" :: Nil, _, GetRequest) =>
      val groupLevel = S.param("group-level").openOr("organization")
      val minContribs_S = S.param("min-contribs").openOr("NA")
      val minContribs = Try(minContribs_S.toInt) match {
        case Success(i) => i
        case Failure(_) => 0
      }
      logger.info(s"groupLevel='$groupLevel', minContribs=$minContribs")
      listContributors(organization, groupLevel, minContribs)
  })

  def listContributors(organization: String, groupLevel: String, minContribs: Int): LiftResponse = {
    val response: List[Contributor] = contributorsByOrganization(organization, groupLevel, minContribs)
    JsonResponse (response.map(_.asJson))
  }

  def contributorsByOrganization(organization: Organization, groupLevel: String, minContribs: Int): List[Contributor] = {
    val sdf = new java.text.SimpleDateFormat("dd-MM-yyyy hh:mm:ss")
    val initialInstant = Instant.now
    logger.info(s"Starting ContribsGH-P-C REST API call at ${sdf.format(Date.from(initialInstant))} - organization='$organization'")

    val repos = reposByOrganization(organization)

    // parallel retrieval of contributors by repo with cache
    val contributorsDetailed: List[Contributor] = RestServerAux.contributorsDetailedFutureWithCache(organization, repos)

    // grouping, sorting
    val (contributorsGroupedAboveMin, contributorsGroupedBelowMin) = contributorsDetailed.
      map(c => if (groupLevel == "repo") c else c.copy(repo=s"All $organization repos")).
      groupBy(c => (c.repo, c.contributor)).
      mapValues(_.foldLeft(0)((acc, elt) => acc + elt.contributions)).
      map(p => Contributor(p._1._1, p._1._2, p._2)).
      partition(_.contributions >= minContribs)

    val contributorsGrouped =
      (
      contributorsGroupedAboveMin
      ++
      contributorsGroupedBelowMin.
        map(c => c.copy(contributor = "Other contributors")).
        groupBy(c => (c.repo, c.contributor)).
        mapValues(_.foldLeft(0)((acc, elt) => acc + elt.contributions)).
        map(p => Contributor(p._1._1, p._1._2, p._2))
      ).toList.sortWith { (c1: Contributor, c2: Contributor) =>
        if (c1.repo != c2.repo) c1.repo < c2.repo
        else if (c1.contributor == "Other contributors") false
        else if (c1.contributions != c2.contributions) c1.contributions >= c2.contributions
        else c1.contributor < c2.contributor
      }

    val finalInstant = Instant.now
    logger.info(s"Finished ContribsGH-P-C REST API call at ${sdf.format(Date.from(finalInstant))} - organization='$organization'")
    logger.info(f"Time elapsed from start to finish: ${Duration.between(initialInstant, finalInstant).toMillis/1000.0}%3.2f seconds")

    contributorsGrouped
  }

}

object RestServerAux {

  import redis.embedded.RedisServer
  import redis.clients.jedis.Jedis
  import collection.JavaConverters._

  // start Redis server
  try {
    val redisServer = new RedisServer(6379)
    redisServer.start()
  } catch {
    case _: Throwable => ()
  }
  // start Redis client
  val redisClient = new Jedis()
  // TODO stop Redis server and client at Jetty shutdown time

  def contributorsDetailedFutureWithCache(organization: Organization, repos: List[Repository]): List[Contributor] = {
    val (reposUpdatedInCache, reposNotUpdatedInCache) = repos.partition(repoUpdatedInCache(organization, _))
    val contributorsDetailed_L_1 =
      reposUpdatedInCache.map { repo =>
        retrieveContributorsFromCache(organization, repo)
      }
    val contributorsDetailed_L_F_2: List[Future[List[Contributor]]] =
      reposNotUpdatedInCache.map { repo =>
        Future { contributorsByRepo(organization, repo) }
      }
    val contributorsDetailed_F_L_2: Future[List[List[Contributor]]] = Future.sequence(contributorsDetailed_L_F_2)
    val contributorsDetailed_L_2 = Await.result(contributorsDetailed_F_L_2, timeout)

    // updates cache with non-existent or recently-modified repos
    val contributorsDetailed_L = contributorsDetailed_L_1 ++ contributorsDetailed_L_2
    contributorsDetailed_L.foreach { contribs_L =>
      if (contribs_L.length > 0) {
        val repoK = organization.trim + "-" + contribs_L.head.repo
        reposNotUpdatedInCache.find(r => (organization.trim + "-" + r.name) == repoK) match {
          case Some(repo) =>
            saveContributorsToCache(organization, repo, contribs_L)
          case None =>
            ()
        }
      }
    }

    contributorsDetailed_L.flatten
  }

  private def contribToString(c: Contributor) = c.contributor.trim + ":" + c.contributions

  private def stringToContrib(r: Repository, s: String) = {
    val v = s.split(":").toVector
    Contributor(r.name, v(0).trim, v(1).trim.toInt)
  }

  private def buildRepoK(o:Organization, r: Repository) = o.trim + "-" + r.name

  private def saveContributorsToCache(org:Organization, repo: Repository, contributors: List[Contributor]) = {
    val repoK = buildRepoK(org, repo)
    redisClient.del(repoK)
    logger.info(s"repo '$repoK' stored in cache")
    contributors.foreach { c: Contributor =>
      redisClient.lpush(repoK, contribToString(c))
    }
    redisClient.lpush(repoK, s"updatedAt:${repo.updatedAt.toString}")
  }

  private def repoUpdatedInCache(org:Organization, repo: Repository): Boolean = {
    val repoK = buildRepoK(org, repo)
    redisClient.lrange(repoK, 0, 0).asScala.toList match {
      case s :: _ =>
        val cachedUpdatedAt = Instant.parse(s.substring(s.indexOf(":") + 1))
        cachedUpdatedAt.compareTo(repo.updatedAt) >= 0
      case _ => false
    }
  }

  private def retrieveContributorsFromCache(org:Organization, repo: Repository) = {
    val repoK = buildRepoK(org, repo)
    val res =redisClient.lrange(repoK, 1, redisClient.llen(repoK).toInt - 1).asScala.toList
    logger.info(s"repo '$repoK' retrieved from cache, # of contributors=${res.length}")
    res.map(s => stringToContrib(repo, s))
  }

}
