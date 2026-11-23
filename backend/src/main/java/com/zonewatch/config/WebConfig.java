package com.zonewatch.config;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Every instance serves the console's static files, so a rolling upgrade replaces API and console
 * together and the load balancer itself never has to be replaced. Hashed asset files are immutable.
 */
@Configuration
@ConditionalOnWebApplication
public class WebConfig implements WebMvcConfigurer {

    private final String consoleDir;

    public WebConfig(@Value("${ZW_CONSOLE_DIR:/app/console}") String consoleDir) {
        this.consoleDir = consoleDir;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/assets/**")
                .addResourceLocations("file:" + consoleDir + "/assets/")
                .setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable());
    }
}
