package org.triplea.ai.benchmark.runner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class KataCatalogTest {
  private static final String VALID =
      """
      start: {map: game.xml}
      seats: [Axis]
      limits: {rounds: 3}
      """;

  private final KataParser parser = new KataParser(List.of("Hard"), List.of("tuv"));

  @TempDir Path root;

  private void kata(final String folder, final String yaml) throws IOException {
    Files.createDirectories(root.resolve(folder));
    Files.writeString(root.resolve(folder).resolve(KataParser.DESCRIPTOR), yaml);
  }

  @Test
  void namedIgnoresABrokenKataElsewhere() throws IOException {
    kata("good", VALID);
    kata("broken", "bogus_key: 1\n");

    assertThat(new KataCatalog(root, parser).named("good").name()).isEqualTo("good");
  }

  @Test
  void namedFindsAKataWhoseNameDiffersFromItsFolder() throws IOException {
    kata("folder", "name: renamed\n" + VALID);

    assertThat(new KataCatalog(root, parser).named("renamed").name()).isEqualTo("renamed");
  }

  @Test
  void namedRejectsAnUnknownKata() throws IOException {
    kata("good", VALID);

    assertThatThrownBy(() -> new KataCatalog(root, parser).named("missing"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("missing");
  }
}
