package info.staticfree.mqtt_camera.image;

import android.media.Image;
import android.support.annotation.NonNull;

import java.nio.ByteBuffer;

import info.staticfree.mqtt_camera.mqtt.MqttRemote;

/**
 * Publishes the image to an MQTT subTopic.
 */
public class ImagePublisher implements Runnable {
    private final Image image;
    private final String subTopic;
    private final MqttRemote mqttRemote;

    public ImagePublisher(@NonNull Image image, @NonNull MqttRemote mqttRemote,
            @NonNull String subTopic) {
        this.image = image;
        this.mqttRemote = mqttRemote;
        this.subTopic = subTopic;
    }

    @Override
    public void run() {
        ByteBuffer buffer = image.getPlanes()[0].getBuffer();
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        try {
            mqttRemote.publish(subTopic, bytes);
        } finally {
            image.close();
        }
    }
}
