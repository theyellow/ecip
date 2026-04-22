package io.emcip.perf;

import io.gatling.javaapi.core.*;
import io.gatling.javaapi.http.*;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

public class PolicyEngineSimulation extends Simulation {

    private final HttpProtocolBuilder httpProtocol =
            http.baseUrl("http://localhost:9083")
                    .acceptHeader("application/json")
                    .contentTypeHeader("application/json");

    private final ScenarioBuilder scn =
            scenario("Policy Evaluation")
                    .exec(
                            http("Evaluate Policy")
                                    .get("/actuator/health")
                                    .check(status().is(200)));

    {
        setUp(
                        scn.injectOpen(
                                rampUsers(100).during(30), constantUsersPerSec(20).during(60)))
                .protocols(httpProtocol)
                .assertions(
                        global().responseTime().percentile(95).lt(100),
                        global().successfulRequests().percent().gt(99.0));
    }
}
