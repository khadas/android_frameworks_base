package com.android.server.khadas;

import android.content.Context;
import android.util.Log;
import com.android.server.SystemService;

public final class KhadasApiService extends SystemService {

    private static final String TAG = "KhadasApiService";
    final KhadasApiServiceImpl mImpl;

    public KhadasApiService(Context context) {
        super(context);
        mImpl = new KhadasApiServiceImpl(context);
    }

    @Override
    public void onStart() {
        Log.i(TAG, "Registering service " + Context.KHADAS_API_SERVICE);
        publishBinderService(Context.KHADAS_API_SERVICE, mImpl);
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == SystemService.PHASE_SYSTEM_SERVICES_READY) {
            mImpl.start();
        }
    }
}
