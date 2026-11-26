package org.catalogueoflife.garrulus.indexer.layer.oai;

import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.w3c.dom.Element;

/**
 * One OAI-PMH record. {@code metadata} is the root element of the metadata payload (the {@code dc} or the JATS
 * {@code article}), null for a deleted record; {@code prefix} is the metadata prefix it was requested with.
 */
public record OaiRecord(String identifier, boolean deleted, @Nullable Instant datestamp, List<String> sets,
    @Nullable Element metadata, String prefix) {
}
