package com.alibaba.otter.canal.protocol;

public class SequenceEntry {
    CanalEntry.Entry entry;
    long sequence;

    public CanalEntry.Entry getEntry() {
        return entry;
    }

    public void setEntry(CanalEntry.Entry entry) {
        this.entry = entry;
    }

    public long getSequence() {
        return sequence;
    }

    public void setSequence(long sequence) {
        this.sequence = sequence;
    }

    public SequenceEntry(CanalEntry.Entry entry, long sequence) {
        this.entry = entry;
        this.sequence = sequence;
    }
}