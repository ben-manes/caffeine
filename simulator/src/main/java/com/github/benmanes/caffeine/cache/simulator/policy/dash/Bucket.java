package com.github.benmanes.caffeine.cache.simulator.policy.dash;

import java.util.ArrayList;
import java.util.List;

public class Bucket {

    public List<Data> data_list = new ArrayList<>();
    public int size;
    public int count = 0;

    public Bucket(int size) {
        this.size = size;
    }

    public boolean isFull() {
        return this.size == this.count;
    }

    public void evictItem(EvictionPolicy policy) {
        this.count--;
        switch (policy) {
            case FIFO:
            case LRU:
                this.data_list.remove(0);
                break;

            case LIFO:
                this.data_list.remove(this.data_list.size() - 1);
                break;
        }
    }

    public void insert(Data data) {
        data_list.add(data);
        this.count++;
    }

    public void remove(Data data) {
        data_list.remove(data);
        this.count--;
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
