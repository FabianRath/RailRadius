package com.railradius.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VarIntsTest {

    private static final int[] SIGNED = {0, -1, 1, -64, 63, 64, -65, 86_400, -86_400, Integer.MAX_VALUE,
            Integer.MIN_VALUE};

    @Test
    void zigzagGivesSmallNumbersOfEitherSignSmallCodes() {
        assertThat(VarInts.zigzag(0)).isZero();
        assertThat(VarInts.zigzag(-1)).isEqualTo(1);
        assertThat(VarInts.zigzag(1)).isEqualTo(2);
        assertThat(VarInts.zigzag(-64)).isEqualTo(127);
        for (int value : SIGNED) {
            assertThat(VarInts.zigzag(value)).isNotNegative();
            assertThat(VarInts.unzigzag(VarInts.zigzag(value))).isEqualTo(value);
        }
    }

    @Test
    void numbersReadBackInOrderAndSmallOnesTakeOneByte() {
        VarInts.Writer writer = new VarInts.Writer();
        for (int value : SIGNED) {
            writer.add(VarInts.zigzag(value));
        }
        writer.add(Long.MAX_VALUE);

        byte[] bytes = writer.toArray();
        VarInts.Reader reader = new VarInts.Reader(bytes);

        for (int value : SIGNED) {
            assertThat(VarInts.unzigzag(reader.next())).isEqualTo(value);
        }
        assertThat(reader.next()).isEqualTo(Long.MAX_VALUE);
        assertThat(reader.hasNext()).isFalse();
        reader.checkAllRead();
        VarInts.Writer small = new VarInts.Writer();
        small.add(VarInts.zigzag(-64));
        assertThat(small.toArray()).hasSize(1);
    }

    @Test
    void negativeNumbersAreRefused() {
        assertThatThrownBy(() -> new VarInts.Writer().add(-1)).isInstanceOf(IllegalArgumentException.class);
    }
}
