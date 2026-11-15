package org.catalogueoflife.nametracker.indexer;

import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import org.catalogueoflife.nametracker.finder.salience.SalienceProperties;
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
      @Value("${nametracker.salience.title}") double title,
      @Value("${nametracker.salience.abstract}") double abstractWeight,
      @Value("${nametracker.salience.act}") double act,
      @Value("${nametracker.salience.heading}") double heading,
      @Value("${nametracker.salience.share}") double share,
      @Value("${nametracker.salience.repeat}") double repeat,
      @Value("${nametracker.salience.ubiquity}") double ubiquity,
      @Value("${nametracker.salience.ubiquity-full-share}") double ubiquityFullShare,
      @Value("${nametracker.salience.threshold}") double threshold) {
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
