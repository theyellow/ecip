package io.emcip.knowledge.engine.config;

import org.apache.tika.Tika;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TikaConfig {

    @Bean
    public Tika tika() {
        Tika tika = new Tika();
        tika.setMaxStringLength(-1); // disable 100k-char default truncation
        return tika;
    }
}
