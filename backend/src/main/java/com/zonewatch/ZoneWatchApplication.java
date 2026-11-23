package com.zonewatch;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * One image, three modes (ZW_MODE):
 *   serve    (default) the API instance
 *   migrate  apply Flyway migrations up to ZW_FLYWAY_TARGET and exit (one-shot container)
 *   other    one-shot maintenance tasks, see com.zonewatch.tool
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class ZoneWatchApplication {

    public static void main(String[] args) {
        String mode = System.getenv().getOrDefault("ZW_MODE", "serve");
        SpringApplication app = new SpringApplication(ZoneWatchApplication.class);
        if (!"serve".equals(mode)) {
            app.setWebApplicationType(WebApplicationType.NONE);
            System.setProperty("zw.mode", mode);
            if ("migrate".equals(mode)) {
                System.setProperty("spring.flyway.enabled", "true");
            }
        }
        ConfigurableApplicationContext context = app.run(args);
        if (!"serve".equals(mode)) {
            System.exit(SpringApplication.exit(context));
        }
    }
}
