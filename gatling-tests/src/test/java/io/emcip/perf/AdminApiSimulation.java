package io.emcip.perf;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

import io.gatling.javaapi.core.*;
import io.gatling.javaapi.http.*;

public class AdminApiSimulation extends Simulation {

    private final HttpProtocolBuilder httpProtocol =
            http.baseUrl("http://localhost:9087")
                    .acceptHeader("application/json")
                    .contentTypeHeader("application/json");

    private final ScenarioBuilder scn =
            scenario("Admin API Load")
                    .exec(
                            http("List Tenants")
                                    .get("/api/tenants")
                                    .header("X-Tenant-Id", "00000000-0000-0000-0000-000000000001")
                                    .header("Authorization", "Bearer test-token")
                                    .check(status().in(200, 401, 403)));

    {
        setUp(scn.injectOpen(rampUsers(30).during(20), constantUsersPerSec(5).during(60)))
                .protocols(httpProtocol)
                .assertions(
                        global().responseTime().percentile(95).lt(500),
                        global().successfulRequests().percent().gt(95.0));
    }
}
