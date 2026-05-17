package com.zclei.lightbreaker.game;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.zclei.lightbreaker.ble.GloveHand;
import com.zclei.lightbreaker.hit.HitEvent;
import com.zclei.lightbreaker.mural.GeneratedMural;
import org.junit.Test;

public class LightBreakerGameEngineTest {
    @Test
    public void difficultyControlsTileCount() {
        LightBreakerGameEngine engine = new LightBreakerGameEngine();
        GeneratedMural mural = mural();

        assertEquals(150, engine.start(mural, GameDifficulty.Easy).getTotalTiles());
        assertEquals(300, engine.start(mural, GameDifficulty.Standard).getTotalTiles());
        assertEquals(500, engine.start(mural, GameDifficulty.Challenge).getTotalTiles());
    }

    @Test
    public void multiplayerHitsTrackPlayerContribution() {
        LightBreakerGameEngine engine = new LightBreakerGameEngine();
        engine.start(mural(), GameDifficulty.Standard);

        GameSnapshot snapshot = engine.registerHit(hit(1), "player-a");

        assertEquals(1, snapshot.getTotalHits());
        assertEquals(Integer.valueOf(1), snapshot.getPlayerHits().get("player-a"));
    }

    @Test
    public void challengeModeContainsLockedTiles() {
        LightBreakerGameEngine engine = new LightBreakerGameEngine();
        GameSnapshot snapshot = engine.start(mural(), GameDifficulty.Challenge);

        assertTrue(snapshot.getTiles().stream().anyMatch(tile -> tile.getKind() == TileKind.Locked));
    }

    private GeneratedMural mural() {
        return new GeneratedMural(
            "test",
            "Test",
            "自然风光",
            "prompt",
            42,
            "nature",
            null,
            null,
            null
        );
    }

    private HitEvent hit(int sourceCount) {
        return new HitEvent(GloveHand.Left, 1000L + sourceCount, 210, sourceCount);
    }
}
