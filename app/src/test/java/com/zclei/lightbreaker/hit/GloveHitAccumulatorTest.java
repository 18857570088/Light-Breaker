package com.zclei.lightbreaker.hit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.zclei.lightbreaker.ble.GloveHand;
import com.zclei.lightbreaker.ble.GlovePacket;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.Test;

public class GloveHitAccumulatorTest {
    @Test
    public void firstPacketSeedsCounterWithoutCreatingHit() {
        GloveHitAccumulator accumulator = new GloveHitAccumulator();

        List<HitEvent> hits = accumulator.accept(packet(7), 100L);

        assertTrue(hits.isEmpty());
    }

    @Test
    public void createsOneHitPerCountDelta() {
        GloveHitAccumulator accumulator = new GloveHitAccumulator();
        accumulator.accept(packet(7), 100L);

        List<HitEvent> hits = accumulator.accept(packet(10, 188), 200L);

        assertEquals(3, hits.size());
        assertEquals(GloveHand.Left, hits.get(0).getHand());
        assertEquals(188, hits.get(0).getIntensity());
        assertEquals(8, hits.get(0).getSourceCount());
        assertEquals(10, hits.get(hits.size() - 1).getSourceCount());
    }

    @Test
    public void supportsCounterWrapAround() {
        GloveHitAccumulator accumulator = new GloveHitAccumulator();
        accumulator.accept(packet(254), 100L);

        List<HitEvent> hits = accumulator.accept(packet(1), 200L);
        List<Integer> counts = hits.stream().map(HitEvent::getSourceCount).collect(Collectors.toList());

        assertEquals(3, hits.size());
        assertEquals(Arrays.asList(255, 0, 1), counts);
    }

    @Test
    public void dropsImplausiblyLargeJump() {
        GloveHitAccumulator accumulator = new GloveHitAccumulator(12);
        accumulator.accept(packet(2), 100L);

        List<HitEvent> hits = accumulator.accept(packet(80), 200L);

        assertTrue(hits.isEmpty());
    }

    private GlovePacket packet(int count) {
        return packet(count, 120);
    }

    private GlovePacket packet(int count, int power) {
        return new GlovePacket(
            GloveHand.Left,
            1,
            90,
            count,
            0,
            power,
            0,
            ""
        );
    }
}
