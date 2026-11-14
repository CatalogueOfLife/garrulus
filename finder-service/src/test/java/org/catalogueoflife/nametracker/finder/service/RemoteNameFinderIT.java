package org.catalogueoflife.nametracker.finder.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.catalogueoflife.nametracker.finder.api.*;
import org.catalogueoflife.nametracker.finder.remote.RemoteNameFinder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import java.net.URI;
import java.net.http.HttpClient;

/** The real HTTP stack on a random port with a stub finder, called by the real client. */
@SpringBootTest(classes = RemoteNameFinderIT.App.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RemoteNameFinderIT {

  @Configuration
  @EnableAutoConfiguration
  @Import({FindController.class, FinderWebConfig.class})
  static class App {
    @Bean
    NameFinder nameFinder() {
      return new FindControllerTest.StubFinder();
    }
  }

  @Value("${local.server.port}")
  int port;

  @Test
  void remoteFinderReturnsTheStubsResult() {
    RemoteNameFinder remote = new RemoteNameFinder(URI.create("http://localhost:" + port), HttpClient.newHttpClient());
    FinderResult golden = FinderJson.mapper().readValue(TestFiles.read("contract/find-response.json"), FinderResult.class);
    Document doc = FinderJson.mapper().readValue(TestFiles.read("contract/find-request.json"), Document.class);

    assertThat(remote.info()).isEqualTo(golden.info());
    assertThat(remote.find(doc, FinderOptions.defaults())).isEqualTo(golden);
  }
}
