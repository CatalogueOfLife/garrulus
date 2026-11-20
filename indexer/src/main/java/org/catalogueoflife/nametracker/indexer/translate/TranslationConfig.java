package org.catalogueoflife.nametracker.indexer.translate;

import java.net.URI;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The LibreTranslate client exists only when translation is enabled and a url is set. */
@Configuration
class TranslationConfig {

  @Bean
  @ConditionalOnExpression("${nametracker.translation.enabled:false} and !'${nametracker.translation.url:}'.isBlank()")
  TitleTranslator libreTranslate(TranslationProperties p, Clock clock) {
    return new LibreTranslateClient(URI.create(p.url().strip()), p.model(), p.timeout(), clock);
  }
}
