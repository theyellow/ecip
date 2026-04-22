package io.emcip.perf;

import io.gatling.javaapi.core.*;
import io.gatling.javaapi.http.*;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

public class IntentClassifierSimulation extends Simulation {

    private final HttpProtocolBuilder httpProtocol =
            http.baseUrl("http://localhost:9082")
                    .acceptHeader("application/json")
                    .contentTypeHeader("application/json");

    private final ScenarioBuilder scn =
            scenario("Intent Classification")
                    .exec(
                            http("Classify Intent")
                                    .post("/api/intent/classify")
                                    .body(
                                            StringBody(
                                                    "{\"text\": \"Hello, how are you?\","
                                                            + " \"chatId\": 12345, \"senderId\":"
                                                            + " 67890}"))
                                    .check(status().is(200)));

    {
        setUp(
                        scn.injectOpen(
                                rampUsers(50).during(30), constantUsersPerSec(10).during(60)))
                .protocols(httpProtocol)
                .assertions(
                        global().responseTime().percentile(95).lt(200),
                        global().successfulRequests().percent().gt(99.0));
    }
}
