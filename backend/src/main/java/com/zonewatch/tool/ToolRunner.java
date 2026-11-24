package com.zonewatch.tool;

import com.zonewatch.config.ZwProperties;
import java.time.Duration;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * One-shot maintenance jobs, run as ZW_MODE=backfill-source-type in the same image
 * (docker compose run --rm backfill). Settings: ZW_BACKFILL_BATCH (rows per transaction, default 2000),
 * ZW_BACKFILL_PAUSE (pause between batches, default PT0.05S), ZW_BACKFILL_FROM (resume after this key).
 */
@Component
@ConditionalOnProperty(name = "zw.mode", havingValue = "backfill-source-type")
public class ToolRunner implements ApplicationRunner {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Environment env;

    public ToolRunner(JdbcTemplate jdbc, TransactionTemplate tx, Environment env, ZwProperties props) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.env = env;
    }

    @Override
    public void run(ApplicationArguments args) {
        int batch = env.getProperty("ZW_BACKFILL_BATCH", Integer.class, 2000);
        Duration pause = Duration.parse(env.getProperty("ZW_BACKFILL_PAUSE", "PT0.05S"));
        String from = env.getProperty("ZW_BACKFILL_FROM", "");
        new SourceTypeBackfill(jdbc, tx).run(batch, pause, from);
    }
}
