package org.triplea.ai.benchmark.runner;

import games.strategy.engine.framework.startup.ui.PlayerTypes;
import games.strategy.engine.player.Player;
import games.strategy.triplea.ai.pro.ProAi;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** The AIs a kata or the command line can name. Adding one is one more entry here. */
public final class AiRegistry {
  private static final Map<String, PlayerTypes.Type> TYPES = new LinkedHashMap<>();

  static {
    TYPES.put("Hard", PlayerTypes.PRO_AI);
    TYPES.put(
        "Hard Beta",
        new PlayerTypes.Type("Hard Beta (AI)") {
          @Override
          public Player newPlayerWithName(final String name) {
            return new ProAi(name, getLabel(), true);
          }
        });
    TYPES.put("Fast", PlayerTypes.FAST_AI);
    TYPES.put("Easy", PlayerTypes.WEAK_AI);
  }

  private AiRegistry() {}

  public static List<String> names() {
    return List.copyOf(TYPES.keySet());
  }

  /**
   * A player type for {@code ai} that plays the given rounds' turns from scripts; only the Pro AIs
   * can follow a script. {@code created} receives each player it makes, for its log.
   */
  static PlayerTypes.Type scripted(
      final String ai, final Map<Integer, KataScript> scripts, final Consumer<ScriptedAi> created) {
    final boolean beta =
        switch (ai) {
          case "Hard" -> false;
          case "Hard Beta" -> true;
          default ->
              throw new IllegalArgumentException(
                  "Only Hard and Hard Beta can play a scripted turn, not " + ai);
        };
    return new PlayerTypes.Type(type(ai).getLabel()) {
      @Override
      public Player newPlayerWithName(final String name) {
        final ScriptedAi player = new ScriptedAi(name, getLabel(), beta, scripts);
        created.accept(player);
        return player;
      }
    };
  }

  public static PlayerTypes.Type type(final String name) {
    final PlayerTypes.Type type = TYPES.get(name);
    if (type == null) {
      throw new IllegalArgumentException("Unknown AI '" + name + "'; known: " + names());
    }
    return type;
  }
}
