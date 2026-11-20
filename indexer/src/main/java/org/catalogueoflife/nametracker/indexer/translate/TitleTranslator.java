package org.catalogueoflife.nametracker.indexer.translate;

import java.io.IOException;
import java.util.Set;

/** A machine translator of titles into English. */
public interface TitleTranslator {

  /** Stored as the {@code source} of the translation. */
  String engine();

  /** Stored as the {@code model} of the translation: a different model translates a title again on request. */
  String model();

  /** Language codes the translator reads and translates to English. */
  Set<String> sources() throws IOException;

  /**
   * The English translation of {@code text} written in {@code from}.
   *
   * @throws org.catalogueoflife.nametracker.indexer.task.ServiceOutageException when the service is down
   * @throws IOException when this request failed
   */
  String translate(String text, String from) throws IOException;
}
