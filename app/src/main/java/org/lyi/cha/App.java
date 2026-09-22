package org.lyi.cha;

import android.app.Application;

import com.google.android.material.color.DynamicColors;

public class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        // Material You：Android 12+ 自动应用系统动态取色
        DynamicColors.applyToActivitiesIfAvailable(this);
    }
}
