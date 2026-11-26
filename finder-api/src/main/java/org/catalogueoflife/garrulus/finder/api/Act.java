package org.catalogueoflife.garrulus.finder.api;

public record Act(String id, ActType type, String marker, String mentionId, double confidence) {}
