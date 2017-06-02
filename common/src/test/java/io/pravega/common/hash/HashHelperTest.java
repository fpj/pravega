/**
 * Copyright (c) 2017 Dell Inc., or its subsidiaries. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package io.pravega.common.hash;

import java.util.UUID;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

public class HashHelperTest {

    @Test
    public void testLongBits() {
        assertEquals(1.0, HashHelper.longToDoubleFraction(-1), 0.0000001);
        assertEquals(0.0, HashHelper.longToDoubleFraction(0), 0.0000001);
        assertEquals(0.5, HashHelper.longToDoubleFraction(Long.reverse(1)), 0.0000001);
        assertEquals(0.25, HashHelper.longToDoubleFraction(Long.reverse(2)), 0.0000001);
        assertEquals(0.75, HashHelper.longToDoubleFraction(Long.reverse(3)), 0.0000001);
        assertEquals(0.125, HashHelper.longToDoubleFraction(Long.reverse(4)), 0.0000001);
        assertEquals(0.625, HashHelper.longToDoubleFraction(Long.reverse(5)), 0.0000001);
        assertEquals(0.375, HashHelper.longToDoubleFraction(Long.reverse(6)), 0.0000001);
        assertEquals(0.875, HashHelper.longToDoubleFraction(Long.reverse(7)), 0.0000001);
    }

    @Test
    public void testToUUID() {
        UUID uuid = HashHelper.seededWith("Test").toUUID("toUUID");
        assertNotEquals(0, uuid.getMostSignificantBits()); 
        assertNotEquals(0, uuid.getLeastSignificantBits());
        assertNotEquals(Long.MAX_VALUE, uuid.getMostSignificantBits()); 
        assertNotEquals(Long.MAX_VALUE, uuid.getLeastSignificantBits());
        assertNotEquals(-1, uuid.getMostSignificantBits()); 
        assertNotEquals(-1, uuid.getLeastSignificantBits());
        assertNotEquals(Long.MIN_VALUE, uuid.getMostSignificantBits()); 
        assertNotEquals(Long.MIN_VALUE, uuid.getLeastSignificantBits());
    }
    
    @Test
    public void testBytesToUUID() {
        byte[] bytes = new byte[] { 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15 };
        UUID uuid = HashHelper.bytesToUUID(bytes);
        assertEquals(uuid.getMostSignificantBits(), 0x0001020304050607L);
        assertEquals(uuid.getLeastSignificantBits(), 0x08090A0B0C0D0E0FL);
    }
    
}
