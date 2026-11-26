package org.catalogueoflife.garrulus.indexer.translate;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Title translation settings. It is on only when {@code garrulus.translation.enabled} is true and the url
 * ({@code GARRULUS_TRANSLATION_URL}, the local LibreTranslate) is set.
 */
@Component
public record TranslationProperties(
    @Value("${garrulus.translation.enabled:false}") boolean enabled,
    @Value("${garrulus.translation.url:}") String url,
    @Value("${garrulus.translation.model:libretranslate-1.9.6/argos}") String model,
    @Value("${garrulus.translation.timeout:30s}") Duration timeout) {
}
