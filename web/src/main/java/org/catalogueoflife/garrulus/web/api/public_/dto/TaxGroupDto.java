package org.catalogueoflife.garrulus.web.api.public_.dto;

import java.util.List;

public record TaxGroupDto(String name, List<String> parents, List<String> codes, String icon) {}
