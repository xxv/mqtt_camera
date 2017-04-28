package info.staticfree.timelapsecamera.mqtt;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

import org.eclipse.paho.android.service.MqttAndroidClient;
import org.eclipse.paho.client.mqttv3.IMqttActionListener;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;

import java.util.UUID;

public class MqttRemote {
    private static final String TAG = MqttRemote.class.getSimpleName();

    private static final String KEY_UUID = "mqtt_uuid";

    @NonNull
    private final String uuid;
    @NonNull
    private final Context context;
    private final RemoteControlCamera camera;

    private MqttAndroidClient mqttClient;

    public MqttRemote(@NonNull Context context, @NonNull RemoteControlCamera camera) {
        this.context = context;
        this.camera = camera;
        uuid = getOrCreateId();
    }

    public void onPause() {
        if (mqttClient != null) {
            mqttClient.close();
        }
    }

    public void connect() {
        SharedPreferences preferences =
                PreferenceManager.getDefaultSharedPreferences(context);
        if (!preferences.getBoolean("mqtt_remote_enable", false)) {
            return;
        }

        String host = preferences.getString("mqtt_hostname", null);
        int port = Integer.valueOf(preferences.getString("mqtt_port", "0"));
        boolean ssl = preferences.getBoolean("mqtt_ssl", false);

        mqttClient =
                new MqttAndroidClient(context.getApplicationContext(),
                        (ssl ? "ssl://" : "tcp://") + host + ':' + port,
                        "cameraClient");
        mqttClient.setCallback(new MqttCallbackExtended() {
            @Override
            public void connectComplete(boolean reconnect, String serverURI) {
                Toast.makeText(context, "Connected to: " + serverURI, Toast.LENGTH_SHORT)
                        .show();
                try {
                    mqttClient.publish(getMqttSubTopic("status"), "connected".getBytes(), 0, true);
                } catch (MqttException e) {
                    Log.e(TAG, "error publishing status", e);
                }
            }

            @Override
            public void connectionLost(Throwable cause) {
                Log.e(TAG, "Disconnected", cause);
            }

            @Override
            public void messageArrived(String topic, MqttMessage message) throws Exception {
                onMqttMessage(topic, message);
            }

            @Override
            public void deliveryComplete(IMqttDeliveryToken token) {}
        });

        MqttConnectOptions connectOptions = new MqttConnectOptions();
        connectOptions.setWill(getMqttSubTopic("status"), "disconnected".getBytes(), 0, true);
        String username = preferences.getString("mqtt_username", null);

        if (!TextUtils.isEmpty(username)) {
            connectOptions.setUserName("steve");
        }

        String password = preferences.getString("mqtt_password", null);

        if (!TextUtils.isEmpty(password)) {
            connectOptions.setPassword(password.toCharArray());
        }

        connectOptions.setCleanSession(true);
        connectOptions.setAutomaticReconnect(true);

        try {
            Log.d(TAG, "Connecting to MQTT...");
            mqttClient.connect(connectOptions, null, new IMqttActionListener() {
                @Override
                public void onSuccess(IMqttToken asyncActionToken) {
                    try {
                        mqttClient.subscribe(getMqttSubTopic("#"), 0);
                    } catch (MqttException e) {
                        Log.e(TAG, "Error subscribing", e);
                    }
                }

                @Override
                public void onFailure(IMqttToken asyncActionToken, Throwable exception) {
                    Log.e(TAG, "Could not connect", exception);
                }
            });
        } catch (MqttException e) {
            Log.e(TAG, "Error connecting to MQTT server", e);
        }
    }

    public void publish(@NonNull String subTopic, @NonNull byte[] payload) {
        try {
            mqttClient.publish(getMqttSubTopic(subTopic), payload, 0, false);
        } catch (MqttException e) {
            Log.e(TAG, "Error publishing", e);
        }
    }

    @NonNull
    private String getMqttSubTopic(@NonNull String subTopic) {
        return "camera/" + uuid + '/' + subTopic;
    }

    private void onMqttMessage(@NonNull String topic, @NonNull MqttMessage message) {
        if (getMqttSubTopic("shutter").equals(topic)) {
            camera.takePicture();
        } else if (getMqttSubTopic("focus").equals(topic)) {
            camera.refocus();
        } else if (getMqttSubTopic("setting/autofocus").equals(topic)) {
            camera.setAutoFocus("1".equals(new String(message.getPayload())));
        }
    }

    private String getOrCreateId() {
        SharedPreferences preferences =
                PreferenceManager.getDefaultSharedPreferences(context);

        String uuid = preferences.getString(KEY_UUID, null);

        if (uuid == null) {
            uuid = UUID.randomUUID().toString();
            preferences.edit().putString(KEY_UUID, uuid).apply();
        }

        return uuid;
    }

    public interface RemoteControlCamera {
        void takePicture();

        void refocus();

        void setAutoFocus(boolean autoFocus);
    }
}
