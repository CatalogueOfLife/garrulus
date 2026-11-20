package org.catalogueoflife.nametracker.indexer.translate;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Title translation settings. It is on only when {@code nametracker.translation.enabled} is true and the url
 * ({@code NAMETRACKER_TRANSLATION_URL}, the local LibreTranslate) is set.
 */
@Component
public record TranslationProperties(
    @Value("${nametracker.translation.enabled:false}") boolean enabled,
    @Value("${nametracker.translation.url:}") String url,
    @Value("${nametracker.translation.model:libretranslate-1.9.6/argos}") String model,
    @Value("${nametracker.translation.timeout:30s}") Duration timeout) {
}
