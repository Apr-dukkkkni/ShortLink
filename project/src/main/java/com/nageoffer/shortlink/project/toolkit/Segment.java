package com.nageoffer.shortlink.project.toolkit;

import java.util.concurrent.atomic.AtomicLong;

public class Segment {

    private final AtomicLong current;
    private final long max;

    public Segment(long start, long max){
        this.current = new AtomicLong(start);
        //AtomicLong：
        //普通long做current++不是线程安全（会出现并发下 ID 重复）。
        //AtomicLong底层用 CAS，不用 synchronized 锁，就能保证多线程环境下原子自增，高性能。
        this.max = max;
    }
    public long tryNextId(){
        long id = current.getAndIncrement();
        if(id > max){
            throw new IllegalStateException("号段已耗尽");
        }
        return id;
    }

    public boolean hasRemaining(){
        return current.get() <= max;
    }

    public long getCurrent(){
        return current.get();
    }

    public long getMax(){
        return max;
    }


}
