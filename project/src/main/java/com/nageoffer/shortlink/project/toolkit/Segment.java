/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
