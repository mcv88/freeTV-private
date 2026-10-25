package com.gathin.tvhome;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class WeatherTest {

    @Test
    public void wmoCodesGroupIntoWhatTheWidgetDraws() {
        assertEquals(Weather.Sky.CLEAR, Weather.sky(0));
        assertEquals(Weather.Sky.PARTLY, Weather.sky(1));
        assertEquals(Weather.Sky.PARTLY, Weather.sky(2));
        assertEquals(Weather.Sky.CLOUDY, Weather.sky(3));
        assertEquals(Weather.Sky.FOG, Weather.sky(45));
        assertEquals(Weather.Sky.FOG, Weather.sky(48));
        assertEquals(Weather.Sky.RAIN, Weather.sky(51));
        assertEquals(Weather.Sky.RAIN, Weather.sky(63));
        assertEquals(Weather.Sky.RAIN, Weather.sky(81));
        assertEquals(Weather.Sky.SNOW, Weather.sky(73));
        assertEquals(Weather.Sky.SNOW, Weather.sky(86));
        assertEquals(Weather.Sky.THUNDER, Weather.sky(95));
        assertEquals(Weather.Sky.THUNDER, Weather.sky(99));
    }

    @Test
    public void rainSoonReadsTheFifteenMinuteSlots() {
        assertArrayEquals(new int[]{30, -1},
                Weather.rainSoon(new double[]{0, 0, 0.4, 0.4, 0, 0, 0, 0, 0}));
        assertArrayEquals(new int[]{-1, 45},
                Weather.rainSoon(new double[]{0.6, 0.3, 0.2, 0, 0, 0, 0, 0, 0}));
        assertArrayEquals(new int[]{-1, -1},
                Weather.rainSoon(new double[]{0, 0, 0, 0, 0, 0, 0, 0, 0}));
        assertArrayEquals(new int[]{-1, -1},
                Weather.rainSoon(new double[]{0.5, 0.5, 0.5, 0.5, 0.5, 0.5, 0.5, 0.5, 0.5}));
        assertArrayEquals(new int[]{15, -1},
                Weather.rainSoon(new double[]{0.05, 0.2, 0, 0, 0, 0, 0, 0, 0}));
    }
}
