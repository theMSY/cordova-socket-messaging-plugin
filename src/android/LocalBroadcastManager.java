package com.socketservice;

import android.arch.lifecycle.MutableLiveData;
import android.content.Intent;

public class LocalBroadcastManager {
    private static MutableLiveData<Intent> _localBroadcastManager;

    public static MutableLiveData<Intent> getInstance() {
        if (_localBroadcastManager == null) {
            _localBroadcastManager = new MutableLiveData<>();
        }

        return _localBroadcastManager;
    }

}
