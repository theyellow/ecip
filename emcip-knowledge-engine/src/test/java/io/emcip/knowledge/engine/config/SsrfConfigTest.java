package io.emcip.knowledge.engine.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.emcip.common.net.SsrfAllowList;
import java.net.InetAddress;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class SsrfConfigTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withUserConfiguration(SsrfConfig.class)
                    .withConfiguration(AutoConfigurations.of());

    @Test
    void buildsClientWithRedirectsDisabled() {
        runner.run(
                context -> {
                    assertThat(context).hasSingleBean(OkHttpClient.class);
                    OkHttpClient client = context.getBean(OkHttpClient.class);
                    assertThat(client.followRedirects()).isFalse();
                    assertThat(client.followSslRedirects()).isFalse();
                });
    }

    @Test
    void allowListBoundFromProperties() throws Exception {
        runner.withPropertyValues("emcip.ingestion.ssrf.allowed-hosts[0]=10.20.0.0/24")
                .run(
                        context -> {
                            SsrfAllowList list = context.getBean(SsrfAllowList.class);
                            assertThat(list.permits("x", InetAddress.getByName("10.20.0.5")))
                                    .isTrue();
                        });
    }

    @Test
    void emptyAllowListByDefault() throws Exception {
        runner.run(
                context -> {
                    SsrfAllowList list = context.getBean(SsrfAllowList.class);
                    assertThat(list.permits("x", InetAddress.getByName("10.20.0.5"))).isFalse();
                });
    }
}
