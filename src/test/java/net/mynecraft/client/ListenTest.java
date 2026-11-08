package net.mynecraft.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** G13: where a sound comes from, in words, for the camera's heading (yaw 0 looks north, towards -z). */
class ListenTest {
    @Test
    void bearingsFollowTheCamera() {
        assertEquals("devant", SoundEngine.bearing(0, 0, 0, 0, -10));
        assertEquals("derrière", SoundEngine.bearing(0, 0, 0, 0, 10));
        assertEquals("à droite", SoundEngine.bearing(0, 0, 0, 10, 0));
        assertEquals("à gauche", SoundEngine.bearing(0, 0, 0, -10, 0));
        assertEquals("devant à droite", SoundEngine.bearing(0, 0, 0, 10, -10));
        assertEquals("devant", SoundEngine.bearing(Math.PI / 2, 0, 0, -10, 0)); // turned west
    }
}
