package bootstrap.liftweb

import net.liftweb._
import util._
import Helpers._
import common._
import http._
import sitemap._
import Loc._
import code.restService.RestServer
import net.liftmodules.JQueryModule
import net.liftweb.http.ContentSourceRestriction.{Self, UnsafeEval, UnsafeInline}
import net.liftweb.http.js.jquery._


/**
 * A class that's instantiated early and run.  It allows the application
 * to modify lift's environment
 */
class Boot {
  def boot {

    // where to search snippet
    LiftRules.addToPackages("code.restService")

    // REST service
    LiftRules.statelessDispatch.append(RestServer)

    // SiteMap
    val entries = List(
      Menu.i("Home") / "index", // the simple way to declare a menu

      Menu(Loc("Static", Link(List("static"), true, "/static/index"),
	       "Read me")))

    LiftRules.setSiteMap(SiteMap(entries:_*))

    //Show the spinny image when an Ajax call starts
    LiftRules.ajaxStart =
      Full(() => LiftRules.jsArtifacts.show("ajax-loader").cmd)
    
    // Make the spinny image go away when it ends
    LiftRules.ajaxEnd =
      Full(() => LiftRules.jsArtifacts.hide("ajax-loader").cmd)

    // Force the request to be UTF-8
    LiftRules.early.append(_.setCharacterEncoding("UTF-8"))

    // Use HTML5 for rendering
    LiftRules.htmlProperties.default.set((r: Req) =>
      new Html5Properties(r.userAgent))

    //Init the jQuery module, see http://liftweb.net/jquery for more information.
    LiftRules.jsArtifacts = JQueryArtifacts
    JQueryModule.InitParam.JQuery=JQueryModule.JQuery172
    JQueryModule.init()

    LiftRules.securityRules = () => SecurityRules(
      content = Some(
        ContentSecurityPolicy(
          defaultSources = List(Self, UnsafeInline),
          scriptSources = List(Self, UnsafeInline, UnsafeEval)
        )
      )
    )

  }
}
