package code.model

import net.liftweb.json.JsonDSL._
import java.time.Instant

object Entities {

  // entities of the REST service

  type Organization = String

  case class Repository(name: String, updatedAt: Instant)

  case class Contributor(repo: String, contributor: String, contributions: Int) {
    def asJson = {
      ("repo" -> repo) ~
      ("contributor" -> contributor) ~
      ("contributions" -> contributions)
    }
  }

  // auxiliary types for the REST client

  type Body = String
  type Error = String

}
