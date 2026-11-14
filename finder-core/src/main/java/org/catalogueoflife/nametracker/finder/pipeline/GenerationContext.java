package org.catalogueoflife.nametracker.finder.pipeline;

import org.gbif.nameparser.api.NomCode;
import org.jspecify.annotations.Nullable;

public record GenerationContext(@Nullable NomCode codeHint) {}
