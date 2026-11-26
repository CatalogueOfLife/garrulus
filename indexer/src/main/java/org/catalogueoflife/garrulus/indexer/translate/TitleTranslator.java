package org.catalogueoflife.garrulus.indexer.translate;

import java.io.IOException;
import java.util.Set;

/** A machine translator of titles into English. */
public interface TitleTranslator {

  /** Stored as the {@code source} of the translation. */
  String engine();

  /** Stored as the {@code model} of the translation: a different model translates a title again on request. */
  String model();

  /** The languages the translator reads and translates to English, as {@code LanguageCodes} normalises them. */
  Set<String> sources() throws IOException;

  /**
   * The English translation of {@code text} written in {@code from} (a normalised code of {@link #sources()}).
   *
   * @throws org.catalogueoflife.garrulus.indexer.task.ServiceOutageException when the service is down
   * @throws IOException when this request failed
   */
  String translate(String text, String from) throws IOException;
}
