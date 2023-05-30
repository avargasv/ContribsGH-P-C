# ContribsGH-P-C
## The fourth way to implement a REST-API in Scala. Asynchronous version using futures and a REDIS cache.

## 1. Introduction

This fifth and final post of the series started with [link to the first post], will explain in detail parts of 
our fourth solution to a software development problem consisting in:

*The implementation of a REST service based on the GitHub REST API v3 that responds to a GET request at port 8080
and endpoint `/org/{org_name}/contributors` with a list of the contributors and the total number of their contributions
to all repositories of the GitHub organization given by the `org_name` part of the URL.*

As explained in the initial post, our solutions to the problem share a structure consisting of a REST client,
a REST server, and a processing module. The difference between solutions reside mainly in the processing
module, which in the fourth solution makes use of Scala futures to parallelize the calls made to the REST
client (as was made in the second solution) with the addition of a cache, to get a faster response the
second time a request is made for a certain organization. The cache will store the number of contributions made 
by any contributor to each of the repositories of a given organization. The cache will be implemented using Redis, 
an in-memory "data structure server" frequently used for that purpose (and many others, but our restricted 
use of Redis will be limited to that specific use case). The code of the fourth solution can be downloaded 
from [ContribsGH-PC](https://www.example.com).

This post will explain in detail the processing module of the fourth solution. To better understand that explanation,
it may be useful to give a very general explanation of Redis first.

## 2. Redis as an optimal tool for implementing our cache.

Redis is an open source in-memory data structure store that can be used as a database, cache, streaming engine and
message broker. It supports, in an extremely efficient way, the storage and retrieval of data structures such as 
strings, hashes, lists, sets, sorted sets and streams, all of them accessible via keys. **In our program we will 
use only lists of strings accessible via string keys**.  

To have access to Redis' services we will use Jedis, a Java client for Redis (there are many available, this is
a very popular one and it is quite easy to use). Jedis can be used directly from Scala without any problem, 
profiting from one of Scala's advantages mentioned in our first post: its seamless integration with Java´s ecosystem.

Besides, in order not to depend on a working Redis server externally installed, our program will use an embedded
Redis server ( available [here](https://github.com/kstyrc/embedded-redis) ), with all the functionality needed
for our use-case (and of course a lot more). Here, again, we will get good benefits in Scala from a software
component developed for Java.

As said, the power of Redis (embedded or not) greatly exceeds the simple use we make of it in our program. 
If interested, you can find a full description of that power, and an explanation of how to use it
[here](https://redis.io/docs/).

## 3. The processing module of our fourth solution

Going ahead with the discussion of this new solution, the processing module using Scala futures of our 
previous second solution:
```scala
def contributorsDetailedFuture(organization: Organization, repos: List[Repository]): List[Contributor] = {
  val contributorsDetailed_L_F: List[Future[List[Contributor]]] = repos.map { repo =>
    Future { contributorsByRepo(organization, repo) }
  }
  val contributorsDetailed_F_L: Future[List[List[Contributor]]] = Future.sequence(contributorsDetailed_L_F)
  val contributorsDetailed: List[Contributor] = Await.result(contributorsDetailed_F_L, timeout).flatten
  contributorsDetailed
}
```
is replaced in our new fourth solution with the following:
```scala
def contributorsDetailedFutureWithCache(organization: Organization, repos: List[Repository]): List[Contributor] = {
  val (reposUpdatedInCache, reposNotUpdatedInCache) = repos.partition(repoUpdatedInCache(organization, _))
  
  // list of contributors for repos present in the cache
  val contributorsDetailed_L_1 =
    reposUpdatedInCache.map { repo =>
      retrieveContributorsFromCache(organization, repo)
    }
  // list of contributors for repos not present in the cache
  // retrieved using the GitHub API exactly as in the second solution
  val contributorsDetailed_L_F_2: List[Future[List[Contributor]]] =
    reposNotUpdatedInCache.map { repo =>
      Future { contributorsByRepo(organization, repo) }
    }
  val contributorsDetailed_F_L_2: Future[List[List[Contributor]]] = Future.sequence(contributorsDetailed_L_F_2)
  val contributorsDetailed_L_2 = Await.result(contributorsDetailed_F_L_2, timeout)
  // list of contributors for all the repos of the organization
  val contributorsDetailed_L = contributorsDetailed_L_1 ++ contributorsDetailed_L_2

  // updates cache with not present (or recently-modified) repos
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
```
Here, we use the `contributorsDetailedFutureWithCache` function to build separately the lists of contributors for
the repos present in the cache and those not yet saved in it, and return the concatenation of those two lists.
To start with, the `repos` list is separated in two, using the standard list function `partition`, which applies 
the `repoUpdatedInCache` predicate to make the separation (this predicate returns `true` if a repo is present and 
up to date in the cache). Then, the list of contributors for the repos present in the cache is built applying the 
function `retrieveContributorsFromCache` to each one of those repos, and the list of contributors for the repos 
missing in the cache (or present in an outdated version, as explained later) is built exactly as in our second 
solution (to be subsequently loaded into the cache, of course).

Three auxiliary functions, used by the function just explained, encapsulate the administration of our Redis cache:
```scala
private def saveContributorsToCache(org:Organization, repo: Repository, contributors: List[Contributor]) = {
  val repoK = buildRepoK(org, repo)
  redisClient.del(repoK)
  contributors.foreach { c: Contributor =>
    redisClient.lpush(repoK, contribToString(c))
  }
  redisClient.lpush(repoK, s"updatedAt:${repo.updatedAt.toString}")
}

private def retrieveContributorsFromCache(org:Organization, repo: Repository) = {
  val repoK = buildRepoK(org, repo)
  val res = redisClient.lrange(repoK, 1, redisClient.llen(repoK).toInt - 1).asScala.toList
  res.map(s => stringToContrib(repo, s))
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
```
To explain these, we need first an explanation of the structure of the Redis' lists used to implement our cache.

Redis' lists allow us to associate a sequence of elements (of type `String` in our case) to a key (in our case a 
`String` in the format <organization name>-<repository name> which, when needed, is built by the function 
`buildRepoK`).

The elements of our lists are `String`s that represent contributions to a repository (in the format 
<contributor name>":"<number of contributions>), exception made of the last one, which represents (in the format
"updatedAt:"<yyyyMMddhhmmss>) the date-time of the last update of the repository involved, as reported by the GitHub API.

All the access to our embedded Redis server is made through a Redis client started simply by executing 
`val redisClient = new Jedis()` at the initialization-time of a `RestServerAux` object, which contains 
all the logic related to the cache, starting with the function `contributorsDetailedFutureWithCache` 
explained above.

Now, for the cache-administration auxiliary functions (all of them defined inside the object `RestServerAux`
also containing `contributorsDetailedFutureWithCache`):

- The first function, `saveContributorsToCache`, makes use of the Redis function `lpush` to push inside a list 
  (under the `String` key representing a particular repo) the contributions of each one of the contributors to that 
  repo in the format previously mentioned. After doing that, the function adds to the list another element 
  representing the time of update of the repo as reported by the GitHub API when retrieving the repos of the 
  current organization (in a format also previously mentioned).
  
- The second function, `retrieveContributorsFromCache`, makes use of the Redis function `lrange` to retrieve, from 
  the list associated to a particular repo, an indexed range (starting at 1) of its elements (in this case all of 
  them except the last one). The length of the list (also index of its last element) is given by the Redis function 
  `llen`. The Java list returned by `lrange` is converted to a Scala `List[String]` using the `asScala` function,
  part of the list conversion functions provided by the standard Scala `collection` library.

- Finally, the third function, `repoUpdatedInCache`, is the predicate used to separate the list of repos of an 
  organization into those present and those not present in the cache. It returns `true` if the list  representing 
  a particular repo exists in the cache **and** its last element contains a date-time of update equal to or later 
  than the current date-time of update of the repo. That date-time comparison express the condition that the repo 
  present in the cache has not been updated in GitHub from the moment it was saved in the cache.

## 4. Benefits of this solution in terms of efficiency

To make a simple comparison of the second and fourth implementations of our REST service, the sequential 
(synchronous) version, named CONTRIBSGH-S, and the (asynchronous) parallel version using futures and a Redis cache, 
named CONTRIBSGH-P-C, we executed both for the organization "revelation", already mentioned in previous posts 
of this series.

The following lines show part of a trace of the executions, displayed by the programs to the logger console.

----------------------------------------------------------------------------------------------------------------

**ContribsGH-S**

[INFO] ContribsGH-S.log - Starting ContribsGH-S REST API call at 03-12-2020 06:55:15 - organization='revelation'

[INFO] ContribsGH-S.log - # of repos=24

[INFO] ContribsGH-S.log - repo='globalize2', # of contributors=6

...

[INFO] ContribsGH-S.log - repo='ey-cloud-recipes', # of contributors=68

[INFO] ContribsGH-S.log - Finished ContribsGH-S REST API call at 03-12-2020 06:55:35 - organization='revelation'

[INFO] ContribsGH-S.log - Time elapsed from start to finish: 20.03 seconds

----------------------------------------------------------------------------------------------------------------

**ContribsGH-P-C**

[INFO] ContribsGH-P-C.log - Starting ContribsGH-P-C REST API call at 24-12-2020 05:00:19 - organization='revelation'

[INFO] ContribsGH-P-C.log - # of repos=24

[INFO] ContribsGH-P-C.log - repo 'globalize2', # of contributors=6

...

[INFO] ContribsGH-P-C.log - repo='rails', # of contributors=351

[INFO] ContribsGH-P-C.log - repo 'revelation-globalize2' stored in cache

...

[INFO] ContribsGH-P-C.log - repo 'revelation-rails' stored in cache

[INFO] ContribsGH-P-C.log - Finished ContribsGH-P-C REST API call at 24-12-2020 05:00:30 - organization='revelation'

[INFO] ContribsGH-P-C.log - Time elapsed from start to finish: 11.02 seconds

----------------------------------------------------------------------------------------------------------------

As you can see, the asynchronous version took about half the time as the synchronous one. The times 
shown are for a laptop with 2 Intel I7 cores. The organization used for the comparison, "revelation", 
has 24 repositories.

The trace of a second call to our ContribsGH-P-C service using the same parameters and organization, shows:

----------------------------------------------------------------------------------------------------------------

[INFO] ContribsGH-P-C.log - Starting ContribsGH-P-C REST API call at 24-12-2020 05:17:47 - organization='revelation'

[INFO] ContribsGH-P-C.log - # of repos=24

[INFO] ContribsGH-P-C.log - repo 'revelation-globalize2' retrieved from cache, # of contributors=6

...

[INFO] ContribsGH-P-C.log - repo 'revelation-rails' retrieved from cache, # of contributors=351

[INFO] ContribsGH-P-C.log - Finished ContribsGH-P-C REST API call at 24-12-2020 05:17:48 - organization='revelation'

[INFO] ContribsGH-P-C.log - Time elapsed from start to finish: 1.23 seconds

----------------------------------------------------------------------------------------------------------------

Here we can see that the elapsed time is reduced to approximately one tenth of that used by the previous call.
This exemplifies the huge gains that can be expected from using our cache implemented as an in-memory Redis 
data-structure server.

## 5. A short evaluation of the alternative solutions to our problem

As we could see analyzing the fourth solution to our problem, the changes needed to provide a cache for 
a previous solution were confined to a very specific and small section of the code. We had to modify only 
one function of our processing module, adding the lines necessary to tell from repos already and not yet 
present in the cache (and acting in consequence) and write just three new auxiliary functions to manage 
the Redis-based cache. In total around 10 lines of code for the former intervention and 15 for the later. 
As we hope the reader will confirm, the resulting new version is just as clear and easy to understand as 
the old one. All the necessary services were provided by Java libraries used directly from our Scala program 
without any harm to the legibility of its code.

In this way we have reached the end of the path traced in the first post of this series. We have used Scala and its
development environment (Java libraries included) as a means to develop a series of solutions to a problem consisting
in the implementation of a REST-API that used another REST-API as a data source. As such, our implementations shared
a structure consisting of three modules: a REST client and a REST server / processing module. The REST client module 
(implemented as the `RestClient` Scala object) was in charge of getting the necessary data from the GitHub REST API. 
The REST server module (implemented as the `RestServer` Scala object) was in charge of generating the responses of 
our REST API, taking as input the GitHub data, previously processed by the processing module (an auxiliary module 
of the server module itself, implemented as the `RestServerAux` Scala object).

The client module used Swift to communicate with GitHub. The server module used Lift to generate the responses. 
Both, the client module and the response-generating part of the server module, did not change between our 
different solutions. The changes were confined to the processing module laying in between the other two, which,
throughout this series of posts, took four different incarnations:
  1. A sequential version where the calls to the GitHub API were made synchronously, waiting for the completion of a 
     call before making the subsequent one.
  2. A parallel version where the calls to the GitHub API were made asynchronously, using Scala futures for that 
     purpose.
  3. Another asynchronous version using Scala Akka actors.
  4. The parallel version using futures with a Redis cache added.

The synchronous version, used mainly as a simple introduction to the general structure of our different solutions,
was, of course, the less efficient one. The version using futures and the version using actors were comparable 
in terms of efficiency, but the actor-based version had an order of magnitude better response the second time it was
called with the same parameters, because the still-alive actors served as a kind of cache. To compensate for this
advantage, a Redis cache was added to the future-based version, resulting in a version completely comparable with
the actor-based one in terms of efficiency, but a lot easier to understand because of the simplicity of its Scala 
code. Besides, strictly speaking, the Akka actors can not be considered a cache, because if an actor system shuts 
down, the state of its actors is lost. Unless, of course, those actors are made persistent ... But that is another 
story, not only because Akka actor persistence is a separate topic in its own right, but also because Akka itself 
can be seen as an almost entirely different software development world, which puts in the hands of a Scala 
programmer an enormous variety of tools specifically oriented to solve the problems posed by all types of
asynchronous systems, including very efficient ways of distributing them, i.e. scaling them not only up to 
the capacity of all the cores available in one computer, but scaling them out to the virtually unlimited 
capacity of a fully distributed computer network.

