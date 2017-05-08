package info.staticfree.mqtt_camera.image;

import android.media.Image;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Saves a JPEG {@link Image} into the specified {@link File}.
 */
class ImageSaver implements Runnable {

    private static final String TAG = ImageSaver.class.getSimpleName();
    /**
     * The JPEG image
     */
    private final Image mImage;
    /**
     * The outputFile we save the image into.
     */
    private final File mFile;

    ImageSaver(Image image, File file) {
        mImage = image;
        mFile = file;
    }

    @Override
    public void run() {
        ByteBuffer buffer = mImage.getPlanes()[0].getBuffer();
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        FileOutputStream output = null;
        try {
            output = new FileOutputStream(mFile);
            output.write(bytes);
        } catch (IOException e) {
            Log.e(TAG, "Error saving image", e);
        } finally {
            mImage.close();
            if (null != output) {
                try {
                    output.close();
                } catch (IOException e) {
                    Log.e(TAG, "Error saving image", e);
                }
            }
        }
    }
}
