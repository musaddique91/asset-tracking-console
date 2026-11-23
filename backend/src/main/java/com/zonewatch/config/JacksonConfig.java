package com.zonewatch.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.zonewatch.common.Times;
import java.io.IOException;
import java.time.Instant;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JacksonConfig {

    /** Every Instant in a response is written as 2026-11-02T06:00:05.120Z. */
    @Bean
    Jackson2ObjectMapperBuilderCustomizer instantFormat() {
        SimpleModule module = new SimpleModule("zw-time");
        module.addSerializer(Instant.class, new JsonSerializer<>() {
            @Override
            public void serialize(Instant value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
                gen.writeString(Times.format(value));
            }
        });
        return builder -> builder.modulesToInstall(module);
    }
}
