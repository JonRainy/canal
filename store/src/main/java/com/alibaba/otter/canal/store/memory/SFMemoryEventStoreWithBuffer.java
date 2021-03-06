package com.alibaba.otter.canal.store.memory;

import com.alibaba.otter.canal.protocol.CanalEntry;
import com.alibaba.otter.canal.protocol.position.LogPosition;
import com.alibaba.otter.canal.protocol.position.Position;
import com.alibaba.otter.canal.protocol.position.PositionRange;
import com.alibaba.otter.canal.store.*;
import com.alibaba.otter.canal.store.helper.CanalEventUtils;
import com.alibaba.otter.canal.store.model.BatchMode;
import com.alibaba.otter.canal.store.model.Event;
import com.alibaba.otter.canal.store.model.Events;
import org.apache.commons.lang.StringUtils;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class SFMemoryEventStoreWithBuffer extends AbstractCanalStoreScavenge implements CanalEventStore<Event>, CanalStoreScavenge, BufferMaxSequence {

    private static final long INIT_SEQUENCE = -1;
    private boolean           ddlIsolation  = false;

    // 记录下put/get/ack操作的三个下标
    private AtomicLong        putSequence   = new AtomicLong(INIT_SEQUENCE);             // 代表当前put操作最后一次写操作发生的位置
    private AtomicLong        getSequence   = new AtomicLong(INIT_SEQUENCE);             // 代表当前get操作读取的最后一条的位置
    private AtomicLong        ackSequence   = new AtomicLong(INIT_SEQUENCE);             // 代表当前ack操作的最后一条的位置

    // 记录下put/get/ack操作的三个memsize大小
    private AtomicLong        putMemSize    = new AtomicLong(0);
    private AtomicLong        getMemSize    = new AtomicLong(0);
    private AtomicLong        ackMemSize    = new AtomicLong(0);


    private int               bufferSize    = 16 * 1024;
    private int               bufferMemUnit = 1024;                                      // memsize的单位，默认为1kb大小
    private int               indexMask;
    private Event[]           entries;
    private CircularBitSet            hasEntry;
    private BatchMode batchMode     = BatchMode.ITEMSIZE;                        // 默认为内存大小模式



    public void start() throws CanalStoreException {
        super.start();
        if (Integer.bitCount(bufferSize) != 1) {
            throw new IllegalArgumentException("bufferSize must be a power of 2");
        }

        indexMask = bufferSize - 1;
        entries = new Event[bufferSize];
        hasEntry = new CircularBitSet(bufferSize);
    }


    public SFMemoryEventStoreWithBuffer() {

    }

    @Override
    public long getMaxSequence() {
        return entries[getIndex(putSequence.get())].getSequence() + bufferSize;
    }

//
//
//    private void profiling(List<Event> events, MemoryEventStoreWithBuffer.OP op) {
//        long localExecTime = 0L;
//        int deltaRows = 0;
//        if (events != null && !events.isEmpty()) {
//            for (Event e : events) {
//                if (localExecTime == 0 && e.getExecuteTime() > 0) {
//                    localExecTime = e.getExecuteTime();
//                }
//                deltaRows += e.getRowsCount();
//            }
//        }
//        switch (op) {
//            case PUT:
//                putTableRows.addAndGet(deltaRows);
//                if (localExecTime > 0) {
//                    putExecTime.lazySet(localExecTime);
//                }
//                break;
//            case GET:
//                getTableRows.addAndGet(deltaRows);
//                if (localExecTime > 0) {
//                    getExecTime.lazySet(localExecTime);
//                }
//                break;
//            case ACK:
//                ackTableRows.addAndGet(deltaRows);
//                if (localExecTime > 0) {
//                    ackExecTime.lazySet(localExecTime);
//                }
//                break;
//            default:
//                break;
//        }
//    }

    private enum OP {
        PUT, GET, ACK
    }


    private boolean isDdl(CanalEntry.EventType type) {
        return type == CanalEntry.EventType.ALTER || type == CanalEntry.EventType.CREATE || type == CanalEntry.EventType.ERASE
                || type == CanalEntry.EventType.RENAME || type == CanalEntry.EventType.TRUNCATE || type == CanalEntry.EventType.CINDEX
                || type == CanalEntry.EventType.DINDEX;
    }

    protected long getMinimumGetOrAck() {
        long get = getSequence.get();
        long ack = ackSequence.get();
        return ack <= get ? ack : get;
    }

    /**
     * 查询是否有空位
     */
    protected boolean checkFreeSlotAt(final long sequence) {
        final long minPoint = getMinimumGetOrAck();
        if (sequence - bufferSize > minPoint) { // 刚好追上一轮
            return false;
        } else {
            // 在bufferSize模式上，再增加memSize控制
            if (batchMode.isMemSize()) {
                final long memsize = putMemSize.get() - ackMemSize.get();
                if (memsize < bufferSize * bufferMemUnit) {
                    return true;
                } else {
                    return false;
                }
            } else {
                return true;
            }
        }
    }


    /**
     * 检查是否存在需要get的数据,并且数量>=batchSize
     */
    protected boolean checkUnGetSlotAt(LogPosition startPosition, int batchSize) {
        if (batchMode.isItemSize()) {
            long current = getSequence.get();
            long maxAbleSequence = putSequence.get();
            long next = current;
            if (startPosition == null || !startPosition.getPostion().isIncluded()) { // 第一次订阅之后，需要包含一下start位置，防止丢失第一条记录
                next = next + 1;// 少一条数据
            }

            if (current < maxAbleSequence && next + batchSize - 1 <= maxAbleSequence) {
                return true;
            } else {
                return false;
            }
        } else {
            // 处理内存大小判断
            long currentSize = getMemSize.get();
            long maxAbleSize = putMemSize.get();

            if (maxAbleSize - currentSize >= batchSize * bufferMemUnit) {
                return true;
            } else {
                return false;
            }
        }
    }

    private long calculateSize(Event event) {
        // 直接返回binlog中的事件大小
        return event.getRawLength();
    }

    private int getIndex(long sequcnce) {
        return (int) sequcnce & indexMask;
    }

    private int getDeltaOffset(Event event) {
        return (int) (event.getSequence() & indexMask);
    }

    protected void doPut(List<Event> data) {

        long current = putSequence.get();
        long end = current + data.size();

        // 先写数据，再更新对应的cursor,并发度高的情况，putSequence会被get请求可见，拿出了ringbuffer中的老的Entry值
        for (Event e : data) {
            entries[getDeltaOffset(e)] = e;
            hasEntry.set(getDeltaOffset(e));
        }

        int putIndex = hasEntry.nextSetBit(current);
        putSequence.set(entries[putIndex].getSequence());

        // 记录一下gets memsize信息，方便快速检索
        if (batchMode.isMemSize()) {
            long size = 0;
            for (Event event : data) {
                size += calculateSize(event);
            }

            putMemSize.getAndAdd(size);
        }
        //profiling(data, MemoryEventStoreWithBuffer.OP.PUT);
        // tell other threads that store is not empty
        //notEmpty.signal();
    }


    @Override
    public void put(List<Event> data) throws InterruptedException, CanalStoreException {
        if (data == null || data.isEmpty()) {
            return;
        }

        doPut(data);
        if (Thread.interrupted()) {
            throw new InterruptedException();
        }
    }

    @Override
    public boolean put(List<Event> data, long timeout, TimeUnit unit) throws InterruptedException, CanalStoreException {
        if (data == null || data.isEmpty()) {
            return true;
        }

        long nanos = unit.toNanos(timeout);
        for (; ;) {
            if (checkFreeSlotAt(putSequence.get() + data.size())) {
                doPut(data);
                return true;
            }
            if (nanos <= 0) {
                return false;
            }

//            try {
//                nanos = notFull.awaitNanos(nanos);
//            } catch (InterruptedException ie) {
//                notFull.signal(); // propagate to non-interrupted thread
//                throw ie;
//            }
        }
    }

    @Override
    public boolean tryPut(List<Event> data) throws CanalStoreException {
        if (data == null || data.isEmpty()) {
            return true;
        }

        if (!checkFreeSlotAt(putSequence.get() + data.size())) {
            return false;
        } else {
            doPut(data);
            return true;
        }

    }

    @Override
    public void put(Event data) throws InterruptedException, CanalStoreException {
        put(Arrays.asList(data));
    }

    @Override
    public boolean put(Event data, long timeout, TimeUnit unit) throws InterruptedException, CanalStoreException {
        return put(Arrays.asList(data), timeout, unit);
    }

    @Override
    public boolean tryPut(Event data) throws CanalStoreException {
        return tryPut(Arrays.asList(data));
    }

    @Override
    public Events<Event> get(Position start, int batchSize) throws InterruptedException, CanalStoreException {
//        try {
//            while (!checkUnGetSlotAt((LogPosition) start, batchSize))
//                notEmpty.await();
//        } catch (InterruptedException ie) {
//            notEmpty.signal(); // propagate to non-interrupted thread
//            throw ie;
//        }

        return doGet(start, batchSize);
    }

    @Override
    public Events<Event> get(Position start, int batchSize, long timeout, TimeUnit unit) throws InterruptedException, CanalStoreException {
        long nanos = unit.toNanos(timeout);
        for (; ; ) {
            if (checkUnGetSlotAt((LogPosition) start, batchSize)) {
                return doGet(start, batchSize);
            }

            if (nanos <= 0) {
                // 如果时间到了，有多少取多少
                return doGet(start, batchSize);
            }

//            try {
//                nanos = notEmpty.awaitNanos(nanos);
//            } catch (InterruptedException ie) {
//                notEmpty.signal(); // propagate to non-interrupted thread
//                throw ie;
//            }

        }
    }

    @Override
    public Events<Event> tryGet(Position start, int batchSize) throws CanalStoreException {
        return doGet(start, batchSize);
    }


    protected Events<Event> doGet(Position start, int batchSize) throws CanalStoreException {
        LogPosition startPosition = (LogPosition) start;

        long current = getSequence.get();
        long maxAbleSequence = putSequence.get();
        long next = current;
        long end = current;
        // 如果startPosition为null，说明是第一次，默认+1处理
        if (startPosition == null || !startPosition.getPostion().isIncluded()) { // 第一次订阅之后，需要包含一下start位置，防止丢失第一条记录
            next = next + 1;
        }

        if (current >= maxAbleSequence) {
            return new Events<Event>();
        }

        Events<Event> result = new Events<Event>();
        List<Event> entrys = result.getEvents();
        long memsize = 0;
        if (batchMode.isItemSize()) {
            end = (next + batchSize - 1) < maxAbleSequence ? (next + batchSize - 1) : maxAbleSequence;
            // 提取数据并返回
            for (; next <= end; next++) {
                Event event = entries[getIndex(next)];
                if (ddlIsolation && isDdl(event.getEventType())) {
                    // 如果是ddl隔离，直接返回
                    if (entrys.size() == 0) {
                        entrys.add(event);// 如果没有DML事件，加入当前的DDL事件
                        end = next; // 更新end为当前
                    } else {
                        // 如果之前已经有DML事件，直接返回了，因为不包含当前next这记录，需要回退一个位置
                        end = next - 1; // next-1一定大于current，不需要判断
                    }
                    break;
                } else {
                    entrys.add(event);
                }
            }
        } else {
            long maxMemSize = batchSize * bufferMemUnit;
            for (; memsize <= maxMemSize && next <= maxAbleSequence; next++) {
                // 永远保证可以取出第一条的记录，避免死锁
                Event event = entries[getIndex(next)];
                if (ddlIsolation && isDdl(event.getEventType())) {
                    // 如果是ddl隔离，直接返回
                    if (entrys.size() == 0) {
                        entrys.add(event);// 如果没有DML事件，加入当前的DDL事件
                        end = next; // 更新end为当前
                    } else {
                        // 如果之前已经有DML事件，直接返回了，因为不包含当前next这记录，需要回退一个位置
                        end = next - 1; // next-1一定大于current，不需要判断
                    }
                    break;
                } else {
                    entrys.add(event);
                    memsize += calculateSize(event);
                    end = next;// 记录end位点
                }
            }

        }

        PositionRange<LogPosition> range = new PositionRange<LogPosition>();
        result.setPositionRange(range);

        range.setStart(CanalEventUtils.createPosition(entrys.get(0)));
        range.setEnd(CanalEventUtils.createPosition(entrys.get(result.getEvents().size() - 1)));
        range.setEndSeq(end);
        // 记录一下是否存在可以被ack的点

        for (int i = entrys.size() - 1; i >= 0; i--) {
            Event event = entrys.get(i);
            // GTID模式,ack的位点必须是事务结尾,因为下一次订阅的时候mysql会发送这个gtid之后的next,如果在事务头就记录了会丢这最后一个事务
            if ((CanalEntry.EntryType.TRANSACTIONBEGIN == event.getEntryType() && StringUtils.isEmpty(event.getGtid()))
                    || CanalEntry.EntryType.TRANSACTIONEND == event.getEntryType() || isDdl(event.getEventType())) {
                // 将事务头/尾设置可被为ack的点
                range.setAck(CanalEventUtils.createPosition(event));
                break;
            }
        }

        if (getSequence.compareAndSet(current, end)) {
            getMemSize.addAndGet(memsize);
//            notFull.signal();
//            profiling(result.getEvents(), MemoryEventStoreWithBuffer.OP.GET);
            return result;
        } else {
            return new Events<Event>();
        }
    }
    @Override
    public Position getLatestPosition() throws CanalStoreException {
        long latestSequence = putSequence.get();
        if (latestSequence > INIT_SEQUENCE && latestSequence != ackSequence.get()) {
            Event event = entries[(int) putSequence.get() & indexMask]; // 最后一次写入的数据，最后一条未消费的数据
            return CanalEventUtils.createPosition(event, true);
        } else if (latestSequence > INIT_SEQUENCE && latestSequence == ackSequence.get()) {
            // ack已经追上了put操作
            Event event = entries[(int) putSequence.get() & indexMask]; // 最后一次写入的数据，included
            // =
            // false
            return CanalEventUtils.createPosition(event, false);
        } else {
            // 没有任何数据
            return null;
        }
    }

    @Override
    public Position getFirstPosition() throws CanalStoreException {
        long firstSeqeuence = ackSequence.get();
        if (firstSeqeuence == INIT_SEQUENCE && firstSeqeuence < putSequence.get()) {
            // 没有ack过数据
            Event event = entries[getIndex(firstSeqeuence + 1)]; // 最后一次ack为-1，需要移动到下一条,included
            // = false
            return CanalEventUtils.createPosition(event, false);
        } else if (firstSeqeuence > INIT_SEQUENCE && firstSeqeuence < putSequence.get()) {
            // ack未追上put操作
            Event event = entries[getIndex(firstSeqeuence)]; // 最后一次ack的位置数据,需要移动到下一条,included
            // = false
            return CanalEventUtils.createPosition(event, false);
        } else if (firstSeqeuence > INIT_SEQUENCE && firstSeqeuence == putSequence.get()) {
            // 已经追上，store中没有数据
            Event event = entries[getIndex(firstSeqeuence)]; // 最后一次ack的位置数据，和last为同一条，included
            // = false
            return CanalEventUtils.createPosition(event, false);
        } else {
            // 没有任何数据
            return null;
        }
    }

    @Override
    public void ack(Position position) throws CanalStoreException {
        cleanUntil(position, -1L);
    }

    @Override
    public void ack(Position position, Long seqId) throws CanalStoreException {
        cleanUntil(position, seqId);
    }

    @Override
    public void rollback() throws CanalStoreException {
        getSequence.set(ackSequence.get());
        getMemSize.set(ackMemSize.get());
    }

    @Override
    public void cleanUntil(Position position) throws CanalStoreException {
        cleanUntil(position, -1L);
    }

    public void cleanUntil(Position position, Long seqId) throws CanalStoreException {
        //final ReentrantLock lock = this.lock;
        //lock.lock();
        try {
            long sequence = ackSequence.get();
            long maxSequence = getSequence.get();

            boolean hasMatch = false;
            long memsize = 0;
            // ack没有list，但有已存在的foreach，还是节省一下list的开销
            long localExecTime = 0L;
            int deltaRows = 0;
            if (seqId > 0) {
                maxSequence = seqId;
            }
            for (long next = sequence + 1; next <= maxSequence; next++) {
                Event event = entries[getIndex(next)];
                if (localExecTime == 0 && event.getExecuteTime() > 0) {
                    localExecTime = event.getExecuteTime();
                }
                deltaRows += event.getRowsCount();
                memsize += calculateSize(event);
                if ((seqId < 0 || next == seqId) && CanalEventUtils.checkPosition(event, (LogPosition) position)) {
                    // 找到对应的position，更新ack seq
                    hasMatch = true;

                    if (batchMode.isMemSize()) {
                        ackMemSize.addAndGet(memsize);
                        // 尝试清空buffer中的内存，将ack之前的内存全部释放掉
                        for (long index = sequence + 1; index < next; index++) {
                            entries[getIndex(index)] = null;// 设置为null
                        }

                        // 考虑getFirstPosition/getLastPosition会获取最后一次ack的position信息
                        // ack清理的时候只处理entry=null，释放内存
                        Event lastEvent = entries[getIndex(next)];
                        lastEvent.setEntry(null);
                        lastEvent.setRawEntry(null);
                    }

                    if (ackSequence.compareAndSet(sequence, next)) {// 避免并发ack
//                        notFull.signal();
//                        ackTableRows.addAndGet(deltaRows);
//                        if (localExecTime > 0) {
//                            ackExecTime.lazySet(localExecTime);
//                        }
                        hasEntry.clear(sequence, next);
                        return;
                    }
                }
            }
            if (!hasMatch) {// 找不到对应需要ack的position
                throw new CanalStoreException("no match ack position" + position.toString());
            }
        } finally {
            //lock.unlock();
        }
    }

    @Override
    public void cleanAll() throws CanalStoreException {
        putSequence.set(INIT_SEQUENCE);
        getSequence.set(INIT_SEQUENCE);
        ackSequence.set(INIT_SEQUENCE);

        putMemSize.set(0);
        getMemSize.set(0);
        ackMemSize.set(0);
        entries = null;
        // for (int i = 0; i < entries.length; i++) {
        // entries[i] = null;
        // }
    }
}
