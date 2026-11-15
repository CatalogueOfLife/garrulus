package org.catalogueoflife.nametracker.web.api.public_;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.catalogueoflife.nametracker.web.AbstractPostgresIT;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.yaml.snakeyaml.Yaml;

/** The hand-written OpenAPI file must list every public GET under /api (not /api/admin). */
class OpenApiCoverageTest extends AbstractPostgresIT {

  @Autowired @org.springframework.beans.factory.annotation.Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mapping;
  @LocalServerPort int port;

  @SuppressWarnings("unchecked")
  static Map<String, Object> yaml() throws Exception {
    try (InputStream in = OpenApiCoverageTest.class.getResourceAsStream("/static/api/openapi.yaml")) {
      assertThat(in).as("static/api/openapi.yaml").isNotNull();
      return new Yaml().load(in);
    }
  }

  @SuppressWarnings("unchecked")
  @Test
  void everyPublicGetMappingIsDocumented() throws Exception {
    Map<String, Object> doc = yaml();
    assertThat(doc.get("openapi").toString()).startsWith("3.1");
    Set<String> documented = ((Map<String, Object>) doc.get("paths")).keySet();
    Set<String> missing = new TreeSet<>();
    for (RequestMappingInfo info : mapping.getHandlerMethods().keySet()) {
      if (!info.getMethodsCondition().getMethods().contains(RequestMethod.GET) || info.getPathPatternsCondition() == null) {
        continue;
      }
      for (String p : info.getPathPatternsCondition().getPatternValues()) {
        if (p.startsWith("/api/") && !p.startsWith("/api/admin")) {
          if (!documented.contains(p)) {
            missing.add(p);
          }
        }
      }
    }
    assertThat(missing).as("public GET mappings missing from openapi.yaml").isEmpty();
    // and nothing documented that does not exist, nothing admin
    assertThat(documented).noneMatch(p -> p.startsWith("/api/admin"));
  }

  @Test
  void openApiFileIsServed() throws Exception {
    HttpResponse<String> r = HttpClient.newHttpClient().send(
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/openapi.yaml")).GET().build(),
        HttpResponse.BodyHandlers.ofString());
    assertThat(r.statusCode()).isEqualTo(200);
    assertThat(r.body()).startsWith("openapi: 3.1");
  }
}
