package dev.agentos.smoketest;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.Color;
import android.view.Gravity;
import android.widget.TextView;

public final class MainActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        TextView view = new TextView(this);
        view.setText(GreetingText.message());
        view.setTextSize(24f);
        view.setTextColor(Color.rgb(20, 36, 45));
        view.setGravity(Gravity.CENTER);
        view.setPadding(24, 24, 24, 24);
        setContentView(view);
    }
}
