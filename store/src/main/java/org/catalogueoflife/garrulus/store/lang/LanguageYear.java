package org.catalogueoflife.garrulus.store.lang;

/** Listed works of one decided language in one year; {@code language} is {@code ''} for works without a decision. */
public record LanguageYear(String language, int year, long works) {}
