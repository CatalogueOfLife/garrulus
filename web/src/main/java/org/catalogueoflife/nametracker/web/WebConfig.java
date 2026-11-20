package org.catalogueoflife.nametracker.web;

import java.nio.file.Path;
import java.time.Clock;
import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.catalogueoflife.nametracker.store.blob.BlobStore;
import org.catalogueoflife.nametracker.store.blob.FileBlobStore;
import org.catalogueoflife.nametracker.store.ident.IdScheme;
import org.catalogueoflife.nametracker.store.ident.IdentifierRegistry;
import org.catalogueoflife.nametracker.store.task.TaskMapper;
import org.catalogueoflife.nametracker.store.task.TaskQueue;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "PT30M")
public class WebConfig {

  /** The identifier schemes: the built-ins plus every {@code IdScheme} bean. */
  @Bean
  IdentifierRegistry identifierRegistry(ObjectProvider<IdScheme> extra) {
    return new IdentifierRegistry(extra.orderedStream().toList());
  }

  @Bean
  TaskQueue taskQueue(TaskMapper mapper) {
    return new TaskQueue(mapper);
  }

  /** Read-only use: web never calls put. */
  @Bean
  BlobStore blobStore(@Value("${nametracker.blobs.dir:./data/blobs}") String dir) {
    return new FileBlobStore(Path.of(dir));
  }

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }

  /** The digest job runs once even with several instances (the shedlock table is shared with the indexer). */
  @Bean
  LockProvider lockProvider(DataSource dataSource) {
    return new JdbcTemplateLockProvider(
        JdbcTemplateLockProvider.Configuration.builder()
            .withJdbcTemplate(new JdbcTemplate(dataSource))
            .usingDbTime()
            .build());
  }
}
