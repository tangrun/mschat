package com.tangrun.mslib.model;

import org.mediasoup.droid.Consumer;
import org.webrtc.MediaStreamTrack;

public class ConsumerWrapper extends WrapperCommon<Consumer> {


    public ConsumerWrapper(String buddyId, String id, String kind, Consumer data) {
        super(buddyId, id, kind, data);
    }

    @Override
    public <T extends MediaStreamTrack> T getTrack() {
        return getData() == null ? null : (T) getData().getTrack();
    }

    @Override
    public void close() {
        if (getData() != null) getData().close();
    }

}
