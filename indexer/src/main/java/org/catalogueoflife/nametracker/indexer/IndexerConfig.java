package org.catalogueoflife.nametracker.indexer;

import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import org.catalogueoflife.nametracker.indexer.index.SalienceProperties;
import org.catalogueoflife.nametracker.store.task.TaskMapper;
import org.catalogueoflife.nametracker.store.task.TaskQueue;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "PT30M")
public class IndexerConfig {

  @Bean
  TaskQueue taskQueue(TaskMapper mapper) {
    return new TaskQueue(mapper);
  }

  /** Salience weights and threshold ({@code nametracker.salience.*}, defaults in application.yml). */
  @Bean
  SalienceProperties salienceProperties(
      @Value("${nametracker.salience.title:0.40}") double title,
      @Value("${nametracker.salience.abstract:0.20}") double abstractWeight,
      @Value("${nametracker.salience.act:0.35}") double act,
      @Value("${nametracker.salience.heading:0.15}") double heading,
      @Value("${nametracker.salience.share:0.25}") double share,
      @Value("${nametracker.salience.repeat:0.15}") double repeat,
      @Value("${nametracker.salience.ubiquity:0.50}") double ubiquity,
      @Value("${nametracker.salience.ubiquity-full-share:0.02}") double ubiquityFullShare,
      @Value("${nametracker.salience.threshold:0.35}") double threshold) {
    return new SalienceProperties(title, abstractWeight, act, heading, share, repeat, ubiquity, ubiquityFullShare,
        threshold);
  }

  /** For scheduled jobs that must run once even with several instances. */
  @Bean
  LockProvider lockProvider(DataSource dataSource) {
    return new JdbcTemplateLockProvider(
        JdbcTemplateLockProvider.Configuration.builder()
            .withJdbcTemplate(new JdbcTemplate(dataSource))
            .usingDbTime()
            .build());
  }
}
