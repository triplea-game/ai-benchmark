package org.triplea.ai.benchmark.runner;

import games.strategy.engine.data.GameData;
import games.strategy.engine.data.GamePlayer;
import games.strategy.engine.data.GameState;
import games.strategy.engine.data.MoveDescription;
import games.strategy.engine.data.ProductionRule;
import games.strategy.engine.data.Route;
import games.strategy.engine.data.Territory;
import games.strategy.engine.data.Unit;
import games.strategy.engine.data.UnitType;
import games.strategy.triplea.ai.pro.ProAi;
import games.strategy.triplea.delegate.Matches;
import games.strategy.triplea.delegate.remote.IAbstractPlaceDelegate;
import games.strategy.triplea.delegate.remote.IMoveDelegate;
import games.strategy.triplea.delegate.remote.IPurchaseDelegate;
import games.strategy.triplea.util.TransportUtils;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import javax.annotation.Nullable;
import org.triplea.java.collections.IntegerMap;

/**
 * A Pro AI player that plays some of its turns from {@link KataScript}s, by round: a kata's way to
 * set up a position by playing a known opening. Phases a script leaves out, and every other turn,
 * are the AI's own. What the script did, and what it could not do, goes to {@link #log()}.
 */
final class ScriptedAi extends ProAi {
  private final Map<Integer, KataScript> scripts;
  private final List<String> log = new ArrayList<>();

  ScriptedAi(
      final String name,
      final String label,
      final boolean beta,
      final Map<Integer, KataScript> scripts) {
    super(name, label, beta);
    this.scripts = Map.copyOf(scripts);
  }

  List<String> log() {
    return List.copyOf(log);
  }

  @Nullable
  private KataScript script(final GameState data) {
    return scripts.get(data.getSequence().getRound());
  }

  @Override
  protected void purchase(
      final boolean bid,
      final int pusToSpend,
      final IPurchaseDelegate purchaseDelegate,
      final GameData data,
      final GamePlayer player) {
    final KataScript script = script(data);
    if (bid || script == null || script.purchase == null) {
      super.purchase(bid, pusToSpend, purchaseDelegate, data, player);
      return;
    }
    final IntegerMap<ProductionRule> rules = new IntegerMap<>();
    script.purchase.forEach((unit, count) -> rules.add(ruleFor(player, unit), count));
    final String error = purchaseDelegate.purchase(rules);
    record(data, "purchase " + script.purchase, error);
    if (error != null) {
      super.purchase(false, pusToSpend, purchaseDelegate, data, player);
      return;
    }
    // Any plans stored by an earlier purchase assumed a different purchase.
    discardStoredMovePlans();
    discardStoredPurchasePlan();
  }

  @Override
  protected void move(
      final boolean nonCombat,
      final IMoveDelegate moveDel,
      final GameData data,
      final GamePlayer player) {
    final KataScript script = script(data);
    final List<KataScript.Move> moves =
        script == null ? null : nonCombat ? script.nonCombatMoves : script.combatMoves;
    if (moves == null) {
      super.move(nonCombat, moveDel, data, player);
      return;
    }
    for (final KataScript.Move move : moves) {
      scriptedMove(nonCombat ? "nonCombat" : "combat", move, moveDel, data, player);
    }
    discardStoredMovePlans();
    if (script.aiFinishesMoves) {
      super.move(nonCombat, moveDel, data, player);
    }
  }

  @Override
  protected void place(
      final boolean bid,
      final IAbstractPlaceDelegate placeDelegate,
      final GameState data,
      final GamePlayer player) {
    final KataScript script = script(data);
    if (!bid && script != null && script.place != null) {
      for (final KataScript.Placement placement : script.place) {
        final Territory at = territory(data, placement.territory);
        final List<Unit> units = new ArrayList<>();
        placement.units.forEach(
            (type, count) -> {
              final List<Unit> ofType =
                  player.getUnitCollection().getMatches(Matches.unitIsOfType(unitType(data, type)));
              ofType.removeAll(units);
              units.addAll(ofType.subList(0, Math.min(count, ofType.size())));
            });
        // The two-argument overload is a default method, which the remote delegate proxy rejects.
        final String error =
            placeDelegate
                .placeUnits(units, at, IAbstractPlaceDelegate.BidMode.NOT_BID)
                .orElse(null);
        record(data, "place " + placement.units + " @ " + placement.territory, error);
      }
      // Whatever is left goes where the AI wants it, not where its purchase plan said.
      discardStoredPurchasePlan();
    }
    if (!player.getUnits().isEmpty()) {
      super.place(bid, placeDelegate, data, player);
    }
  }

  private void scriptedMove(
      final String phase,
      final KataScript.Move move,
      final IMoveDelegate moveDel,
      final GameData data,
      final GamePlayer player) {
    final String what = phase + " " + move.units + " " + move.from + " -> " + destination(move);
    final Territory from = territory(data, move.from);
    final List<Unit> units = new ArrayList<>();
    move.units.forEach(
        (type, count) -> {
          final List<Unit> candidates =
              from.getUnitCollection()
                  .getMatches(
                      Matches.unitIsOwnedBy(player)
                          .and(Matches.unitIsOfType(unitType(data, type))));
          candidates.removeAll(units);
          candidates.sort(Comparator.comparing(Unit::getMovementLeft).reversed());
          units.addAll(candidates.subList(0, Math.min(count, candidates.size())));
        });
    if (units.isEmpty()) {
      record(data, what, "no such units");
      return;
    }
    final Optional<Route> route;
    try {
      route = route(move, from, units, data, player);
    } catch (final IllegalArgumentException e) {
      // A bad route is a script mistake for the log, not a reason to lose the game.
      record(data, what, e.getMessage());
      return;
    }
    if (route.isEmpty() || !route.get().hasSteps()) {
      record(data, what, "no route");
      return;
    }
    final Map<Unit, Unit> transports;
    if (route.get().isSeaLoad()) {
      transports =
          TransportUtils.mapTransportsToLoad(
              units,
              route.get().getEnd().getUnitCollection().getMatches(Matches.unitIsOwnedBy(player)));
    } else if (route.get().isSeaUnload()) {
      transports = TransportUtils.mapTransportsAlreadyLoaded(units, from.getUnits());
    } else {
      transports = Map.of();
    }
    record(
        data,
        what,
        moveDel.performMove(new MoveDescription(units, route.get(), transports)).orElse(null));
  }

  private static String destination(final KataScript.Move move) {
    return move.route != null ? String.join(" -> ", move.route) : move.to;
  }

  private static Optional<Route> route(
      final KataScript.Move move,
      final Territory from,
      final Collection<Unit> units,
      final GameData data,
      final GamePlayer player) {
    if (move.route != null) {
      final List<Territory> path = move.route.stream().map(name -> territory(data, name)).toList();
      if (!path.get(0).equals(from)) {
        throw new IllegalArgumentException("route must start at 'from': " + move.route);
      }
      return Optional.of(new Route(path));
    }
    if (move.to == null) {
      throw new IllegalArgumentException("move needs 'to' or 'route': from " + move.from);
    }
    final Predicate<Territory> through;
    if (units.stream().allMatch(Matches.unitIsAir())) {
      through = t -> true;
    } else if (units.stream().allMatch(Matches.unitIsSea())) {
      through = Matches.territoryIsWater();
    } else {
      through = Matches.territoryIsLand();
    }
    return data.getMap().getRouteForUnits(from, territory(data, move.to), through, units, player);
  }

  private void record(final GameState data, final String action, @Nullable final String error) {
    log.add(
        "round "
            + data.getSequence().getRound()
            + " "
            + getName()
            + ": "
            + action
            + (error == null ? "" : " FAILED: " + error));
  }

  private static ProductionRule ruleFor(final GamePlayer player, final String unit) {
    return player.getProductionFrontier().getRules().stream()
        .filter(
            rule ->
                rule.getName().equals(unit)
                    || rule.getResults().keySet().stream().anyMatch(r -> r.getName().equals(unit)))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException(player.getName() + " cannot buy " + unit));
  }

  private static Territory territory(final GameState data, final String name) {
    final Territory t = data.getMap().getTerritoryOrNull(name);
    if (t == null) {
      throw new IllegalArgumentException("No territory named '" + name + "'");
    }
    return t;
  }

  private static UnitType unitType(final GameState data, final String name) {
    return data.getUnitTypeList().getUnitTypeOrThrow(name);
  }
}
