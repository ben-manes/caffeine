package com.github.benmanes.caffeine.cache.simulator.policy.dash;

import com.github.benmanes.caffeine.cache.simulator.policy.PolicyStats;

import java.util.ArrayList;
import java.util.List;

public class Bucket {

    public List<Data> data_list = new ArrayList<>();
    public int maxSize;
//    public int count = 0;

    public Bucket(int size) {
        this.maxSize = size;
    }

    public boolean isFull() {
        return this.maxSize == this.data_list.size();
    }

    public int size() {
        return this.data_list.size();
    }

    public Data getLFU() {
        Data LFUData = this.data_list.get(0);
        for (Data data : this.data_list) {
            if (data.LFUCounter < LFUData.LFUCounter) {
                LFUData = data;
            }
        }
        return LFUData;
    }

    public void evictItem(DashPolicy.EvictionPolicy policy, PolicyStats policyStats, Data data) {
        policyStats.recordEviction();
//        this.count--;

        if (data != null) {
            this.data_list.remove(data);
            return;
        }

        switch (policy) {
            case MOVE_AHEAD:
            case FIFO:
            case LRU:
                this.data_list.remove(0);
                break;

            case LIFO:
                this.data_list.remove(this.data_list.size() - 1);
                break;

            case LFU:
                this.data_list.remove(this.getLFU());
                break;

            case LFU_DISP:
                break;
        }
    }

    public void insert(Data data) {
        this.data_list.add(data);
//        this.count++;
        if (this.data_list.size() > this.maxSize) {
            System.out.println(String.format("@@@@@@@@@@@@ Bucket overflow (size = %d) @@@@@@@@@@@@", this.data_list.size()));
//            System.exit(0);
        }
    }

    public void remove(Data data) {
        data_list.remove(data);
//        this.count--;
    }

    public Data get(Data data) {
        if (this.data_list.contains(data)) {
            int index = this.data_list.indexOf(data);
            return this.data_list.get(index);
        } else {
            return null;
        }
    }
}
