  <h2>REST API ContribsGH-P</h2>
      A REST service that given the name of a GitHub organization returns a list of contributors 
      (by organization or by repo) in JSON format, sorted by the number of contributions
      <br/>
      <br/>
      Parallel version using Scala futures
      <br/>

  <h2>How to use the service</h2>
      <ol>
        <li>
          Clone the repository
        </li>
        <li>
          Run sbt under the repository's home directory
        </li>
        <li>
          After the sbt prompt 'sbt:ContribsGH-P ->' appears, execute the following sbt command:
        <br/>
          jetty:start
        </li>
        <li>
          Call the rest service with the following url:
          <br/>
          http://localhost:8080/org/{org_name}/contributors?group-level={organization|repo}&min-contribs={integer_value}
        <br/>
          Or play with the service using the page at http://localhost:8080/index (option Home of the menu)
        </li>
        <li>
          The documentation is in the page at http://localhost:8080/static/index (option Read me of the menu)
        </li>
      </ol>
