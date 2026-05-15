package io.emcip.audit.service.config;

import io.r2dbc.postgresql.codec.Json;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.std.StdSerializer;

/** Registers Jackson serializers for R2DBC types used in REST responses. */
@Configuration
public class JacksonConfig {

    /**
     * Teach Jackson 3 how to serialize {@link Json} (JSONB codec type) by writing its content as
     * raw JSON, so {@code details} appears as an embedded object in responses rather than a quoted
     * string.
     */
    @Bean
    public SimpleModule r2dbcJsonModule() {
        SimpleModule module = new SimpleModule("R2dbcJsonModule");
        module.addSerializer(
                Json.class,
                new StdSerializer<>(Json.class) {
                    @Override
                    public void serialize(Json value, JsonGenerator gen, SerializationContext ctxt)
                            throws JacksonException {
                        if (value == null) {
                            gen.writeNull();
                        } else {
                            gen.writeRawValue(value.asString());
                        }
                    }
                });
        return module;
    }
}
