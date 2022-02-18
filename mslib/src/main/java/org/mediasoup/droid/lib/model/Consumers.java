package org.mediasoup.droid.lib.model;

import org.json.JSONObject;
import org.mediasoup.droid.Consumer;
import org.mediasoup.droid.lib.Constant;
import org.mediasoup.droid.lib.CommonInvoker;
import org.mediasoup.droid.lib.WrapperCommon;
import org.mediasoup.droid.lib.lv.SupplierMutableLiveData;
import org.webrtc.MediaStreamTrack;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class Consumers implements CommonInvoker {

    @Override
    public WrapperCommon getCommonInfo(Collection<String> ids,String kind) {
        for (String id : ids) {
            ConsumerWrapper wrapper = getConsumer(id);
            if (wrapper != null && wrapper.getConsumer() != null && kind.equals(wrapper.getConsumer().getKind())) {
                return wrapper;
            }
        }
        return null;
    }

    public static class ConsumerWrapper extends WrapperCommon {

        private String mType;
        private int mSpatialLayer;
        private int mTemporalLayer;
        private Consumer mConsumer;
        private int mPreferredSpatialLayer;
        private int mPreferredTemporalLayer;

        /**
         * 属性变化
         * score paused resumed
         */
        SupplierMutableLiveData<WrapperCommon> consumerWrapperSupplierMutableLiveData;

        ConsumerWrapper(String type, boolean remotelyPaused, Consumer consumer) {
            mType = type;
            mLocallyPaused = false;
            mRemotelyPaused = remotelyPaused;
            mSpatialLayer = -1;
            mTemporalLayer = -1;
            mConsumer = consumer;
            mPreferredSpatialLayer = -1;
            mPreferredTemporalLayer = -1;
            consumerWrapperSupplierMutableLiveData = new SupplierMutableLiveData<>(ConsumerWrapper.this);
        }

        @Override
        public SupplierMutableLiveData<WrapperCommon> getWrapperCommonLiveData() {
            return consumerWrapperSupplierMutableLiveData;
        }

        public String getType() {
            return mType;
        }

        public int getSpatialLayer() {
            return mSpatialLayer;
        }

        public int getTemporalLayer() {
            return mTemporalLayer;
        }

        public Consumer getConsumer() {
            return mConsumer;
        }

        public int getPreferredSpatialLayer() {
            return mPreferredSpatialLayer;
        }

        public int getPreferredTemporalLayer() {
            return mPreferredTemporalLayer;
        }

        private void setLocallyPaused(boolean b) {
            mLocallyPaused = b;
        }

        private void setRemotelyPaused(boolean b) {
            mRemotelyPaused = b;
        }

        private void setConsumerScore(int score) {
            mConsumerScore = score;
        }

        private void setProducerScore(int score) {
            mProducerScore = score;
        }

        @Override
        public MediaStreamTrack getTrack() {
            return mConsumer == null ? null : mConsumer.getTrack();
        }
    }

    private final Map<String, ConsumerWrapper> consumers;

    public Consumers() {
        consumers = new ConcurrentHashMap<>();
    }

    public void addConsumer(String type, Consumer consumer, boolean remotelyPaused) {
        consumers.put(consumer.getId(), new ConsumerWrapper(type, remotelyPaused, consumer));
    }

    public void removeConsumer(String consumerId) {
        consumers.remove(consumerId);
    }

    public void setConsumerPaused(String consumerId, String originator) {
        ConsumerWrapper wrapper = consumers.get(consumerId);
        if (wrapper == null) {
            return;
        }

//        wrapper.getWrapperCommonLiveData().postValue(value -> {
            if (Constant.originator_local.equals(originator)) {
                wrapper.setLocallyPaused(true);
            } else {
                wrapper.setRemotelyPaused(true);
            }
//        });
    }

    public void setConsumerResumed(String consumerId, String originator) {
        ConsumerWrapper wrapper = consumers.get(consumerId);
        if (wrapper == null) {
            return;
        }

//        wrapper.getWrapperCommonLiveData().postValue(value -> {
            if (Constant.originator_local.equals(originator)) {
                wrapper.setLocallyPaused(false);
            } else {
                wrapper.setRemotelyPaused(false);
            }
//        });
    }


    public boolean setConsumerScore(String consumerId, JSONObject score) {
        ConsumerWrapper wrapper = consumers.get(consumerId);
        if (wrapper == null) {
            return false;
        }
        try {
            //{"consumerId":"f0aaaad6-cf61-40c6-9f51-a75ef07243bd","score":{"producerScore":10,"producerScores":[10],"score":10}}
            int producerScore = score.optInt("producerScore");
            int consumerScore = score.optInt("score");
//            wrapper.getWrapperCommonLiveData().postValue(value -> {
                wrapper.setConsumerScore(consumerScore);
                wrapper.setProducerScore(producerScore);
//            });
            return true;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return false;
    }

    public void setConsumerCurrentLayers(String consumerId, int spatialLayer, int temporalLayer) {
        ConsumerWrapper wrapper = consumers.get(consumerId);
        if (wrapper == null) {
            return;
        }

//        wrapper.getWrapperCommonLiveData().postValue(value -> {
            wrapper.mSpatialLayer = spatialLayer;
            wrapper.mTemporalLayer = temporalLayer;
//        });

    }

    public ConsumerWrapper getConsumer(String consumerId) {
        return consumers.get(consumerId);
    }

    public void clear() {
        consumers.clear();
    }
}
