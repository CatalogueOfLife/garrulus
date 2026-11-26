package org.catalogueoflife.garrulus.indexer;

import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import org.catalogueoflife.garrulus.finder.salience.SalienceProperties;
import org.catalogueoflife.garrulus.indexer.hits.HitProperties;
import org.catalogueoflife.garrulus.indexer.lang.LanguageProperties;
import org.catalogueoflife.garrulus.indexer.xr.XrSwitchProperties;
import org.catalogueoflife.garrulus.store.ident.IdScheme;
import org.catalogueoflife.garrulus.store.ident.IdentifierRegistry;
import org.catalogueoflife.garrulus.store.task.TaskMapper;
import org.catalogueoflife.garrulus.store.task.TaskQueue;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
@EnableSchedulerLock(defaultLockAtMostFor = "PT30M")
public class IndexerConfig {

  /** The identifier schemes: the built-ins plus every {@code IdScheme} bean. */
  @Bean
  IdentifierRegistry identifierRegistry(ObjectProvider<IdScheme> extra) {
    return new IdentifierRegistry(extra.orderedStream().toList());
  }

  @Bean
  TaskQueue taskQueue(TaskMapper mapper) {
    return new TaskQueue(mapper);
  }

  /** Salience weights and threshold ({@code garrulus.salience.*}, defaults in application.yml). */
  @Bean
  SalienceProperties salienceProperties(
      @Value("${garrulus.salience.title}") double title,
      @Value("${garrulus.salience.abstract}") double abstractWeight,
      @Value("${garrulus.salience.act}") double act,
      @Value("${garrulus.salience.heading}") double heading,
      @Value("${garrulus.salience.share}") double share,
      @Value("${garrulus.salience.repeat}") double repeat,
      @Value("${garrulus.salience.ubiquity}") double ubiquity,
      @Value("${garrulus.salience.ubiquity-full-share}") double ubiquityFullShare,
      @Value("${garrulus.salience.threshold}") double threshold) {
    return new SalienceProperties(title, abstractWeight, act, heading, share, repeat, ubiquity, ubiquityFullShare,
        threshold);
  }

  /** Hit window ({@code garrulus.hits.*}). */
  @Bean
  HitProperties hitProperties(
      @Value("${garrulus.hits.window-days}") int windowDays,
      @Value("${garrulus.hits.future-days}") int futureDays) {
    return new HitProperties(windowDays, futureDays);
  }

  /** XR switch gate and pre-match parallelism ({@code garrulus.xr.*}, {@code garrulus.clb.match-parallelism}). */
  @Bean
  XrSwitchProperties xrSwitchProperties(
      @Value("${garrulus.xr.max-lost-share:0.05}") double maxLostShare,
      @Value("${garrulus.clb.match-parallelism:8}") int matchParallelism) {
    return new XrSwitchProperties(maxLostShare, matchParallelism);
  }

  /** Language detection and decision ({@code garrulus.language.*}). */
  @Bean
  LanguageProperties languageProperties(
      @Value("${garrulus.language.min-confidence:0.80}") float minConfidence,
      @Value("${garrulus.language.min-margin:0.10}") float minMargin,
      @Value("${garrulus.language.min-title-words:3}") int minTitleWords,
      @Value("${garrulus.language.min-text-letters:100}") int minTextLetters,
      @Value("${garrulus.language.high-accuracy:false}") boolean highAccuracy) {
    return new LanguageProperties(minConfidence, minMargin, minTitleWords, minTextLetters, highAccuracy);
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
