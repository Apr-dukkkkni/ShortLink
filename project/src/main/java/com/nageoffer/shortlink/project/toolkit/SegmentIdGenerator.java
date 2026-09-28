package com.nageoffer.shortlink.project.toolkit;

import com.nageoffer.shortlink.project.dao.ShortLinkSequenceMapper;
import com.oracle.truffle.api.object.dsl.Volatile;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;


@Component
@RequiredArgsConstructor
public class SegmentIdGenerator implements IdGenerator{

    private final ShortLinkSequenceMapper shortLinkSequenceMapper;
    private final Object lock = new Object();
    private volatile Segment currentSegment;

    @Override
    public long nextId(){
        Segment segment = currentSegment;

        if (segment == null || segment.isExhausten()){
            synchronized (lock) {
                segment = currentSegment;

                if(segment == null || segment.isExhausten()){
                    currentSegment = loadSegment();
                }
                segment = currentSegment;
            }
        }
        return segment.nextId();
    }
    private Segment loadSegment() {
        //return shortLinkSequenceMapper.allocateSegment("short_link");
        return
    }

}
