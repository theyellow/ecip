package io.emcip.adminui;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Collections;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

@RestController
@RequestMapping("/api")
public class ApiProxyController {

    private static final Logger log = LoggerFactory.getLogger(ApiProxyController.class);

    private static final Set<String> HOP_BY_HOP_HEADERS =
            Set.of(
                    "connection",
                    "keep-alive",
                    "transfer-encoding",
                    "upgrade",
                    "proxy-authorization",
                    "proxy-authenticate",
                    "te",
                    "trailer",
                    "host");

    private final RestClient restClient;

    public ApiProxyController(
            @Value("${admin.api.url:http://emcip-admin-api:9087}") String adminApiUrl) {
        this.restClient = RestClient.builder().baseUrl(adminApiUrl).build();
    }

    @RequestMapping("/**")
    public ResponseEntity<byte[]> proxy(
            HttpServletRequest request, @RequestBody(required = false) byte[] body)
            throws IOException {
        String uri = request.getRequestURI();
        String queryString = request.getQueryString();
        if (queryString != null) {
            uri = uri + "?" + queryString;
        }

        HttpMethod method = HttpMethod.valueOf(request.getMethod());
        log.debug("Proxying {} {}", method, uri);

        RestClient.RequestBodySpec spec =
                restClient
                        .method(method)
                        .uri(uri)
                        .headers(
                                headers ->
                                        Collections.list(request.getHeaderNames()).stream()
                                                .filter(
                                                        name ->
                                                                !HOP_BY_HOP_HEADERS.contains(
                                                                        name.toLowerCase()))
                                                .forEach(
                                                        name ->
                                                                headers.add(
                                                                        name,
                                                                        request.getHeader(name))));

        if (body != null && body.length > 0) {
            spec.body(body);
        }

        return spec.exchange(
                (req, res) -> {
                    byte[] responseBody = res.getBody().readAllBytes();
                    ResponseEntity.BodyBuilder builder = ResponseEntity.status(res.getStatusCode());
                    res.getHeaders()
                            .forEach(
                                    (name, values) -> {
                                        if (!HOP_BY_HOP_HEADERS.contains(name.toLowerCase())) {
                                            builder.header(name, values.toArray(new String[0]));
                                        }
                                    });
                    return builder.body(responseBody);
                });
    }
}
