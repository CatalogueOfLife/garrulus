package org.catalogueoflife.nametracker.indexer.registry;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class IssnlSwitchTest {

  @Test
  void switchOffMakesNoCall() throws Exception {
    // a null mapper, datasource and URL that is no URL: any use would throw
    IssnlRefreshHandler h = new IssnlRefreshHandler(null, null, null, null, false, "not a url", Duration.ofSeconds(1));
    h.handle(null);
  }

  @Test
  void issnlDefaultsOff() throws Exception {
    String yml = Files.readString(Path.of("src/main/resources/application.yml"));
    Matcher m = Pattern.compile("issnl:\\s*\\n(?:\\s*#.*\\n)*\\s*enabled: \\$\\{[A-Z_]+:(\\w+)}").matcher(yml);
    assertThat(m.find()).isTrue();
    assertThat(m.group(1)).isEqualTo("false");
  }
}
