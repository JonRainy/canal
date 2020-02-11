package com.alibaba.otter.canal.store.memory;

import java.util.BitSet;

public class CircularBitSet {

    private final static int ADDRESS_BITS_PER_WORD = 6;
    private final static int BITS_PER_WORD = 1 << ADDRESS_BITS_PER_WORD;
    private static final long WORD_MASK = 0xffffffffffffffffL;

    private long[] words;
    private int nbits;



    public CircularBitSet(int nbits) {
        if (nbits < 0)
            throw new NegativeArraySizeException("nbits < 0: " + nbits);

        initWords(nbits);
    }



    private void initWords(int nbits) {
        this.nbits = nbits;
        words = new long[wordIndex(nbits-1) + 1];
    }

    private int wordIndex(long bitIndex) {
        return (int) (bitIndex  >> ADDRESS_BITS_PER_WORD);
    }


    public void set(long bitIndex) {
        if (bitIndex < 0)
            throw new IndexOutOfBoundsException("bitIndex < 0: " + bitIndex);

        bitIndex = bitIndex % nbits;
        int wordIndex = wordIndex(bitIndex);

        words[wordIndex] |= (1L << bitIndex);
    }


    public void set(long bitIndex, boolean value) {
        if (value)
            set(bitIndex);
        else
            clear(bitIndex);
    }

    public void clear(long bitIndex) {
        if (bitIndex < 0)
            throw new IndexOutOfBoundsException("bitIndex < 0: " + bitIndex);

        bitIndex = bitIndex % nbits;

        int wordIndex = wordIndex(bitIndex);

        words[wordIndex] &= ~(1L << bitIndex);
    }


    public void clear(long startIndex, long endIndex) {
        if (startIndex < 0)
            throw new IndexOutOfBoundsException("startIndex < 0: " + startIndex);

        if (endIndex < 0)
            throw new IndexOutOfBoundsException("endIndex < 0: " + startIndex);

        if (startIndex > endIndex)
            throw new IndexOutOfBoundsException("startIndex < endIndex: " + startIndex + "  " + endIndex);

        for (long idx = startIndex; idx < endIndex; idx++) {
            int wordIndex = wordIndex(idx % nbits);

            words[wordIndex] &= ~(1L << startIndex);
        }
    }


    public void clear() {
        for (int i=0; i<words.length; i++) {
            words[i] = 0;
        }
    }

    public int nextSetBit(long fromIndex) {
        if (fromIndex < 0 )
            throw new IndexOutOfBoundsException("fromIndex < 0: " + fromIndex);

        fromIndex = fromIndex % nbits;

        int startWord = wordIndex(fromIndex);
        int endWord = startWord + words.length;

        long word = words[startWord] & (WORD_MASK << fromIndex);

        int u = startWord;
        do {
            if (word != 0)
                return ((u % words.length) * BITS_PER_WORD) + Long.numberOfTrailingZeros(word);

            ++u;
            word = words[(u % words.length)];
        } while (u < endWord);

        word = word & (WORD_MASK >> (BITS_PER_WORD - (fromIndex % BITS_PER_WORD)));
        if (word !=0 ) {
            return ((u % words.length) * BITS_PER_WORD) + Long.numberOfTrailingZeros(word);
        }
        return -1;
    }

    public static void main(String[]args) {
        CircularBitSet c = new CircularBitSet(1024);
        c.set(63);
        System.out.println(c.nextSetBit(63));
    }

}
