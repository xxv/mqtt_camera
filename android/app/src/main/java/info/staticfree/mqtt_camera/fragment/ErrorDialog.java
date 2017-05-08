package info.staticfree.mqtt_camera.fragment;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.os.Bundle;

/**
 * Shows an error message dialog.
 */
public class ErrorDialog extends DialogFragment {

    private static final String ARG_MESSAGE = "message";

    public static ErrorDialog newInstance(String message) {
        ErrorDialog dialog = new ErrorDialog();
        Bundle args = new Bundle();
        args.putString(ARG_MESSAGE, message);
        dialog.setArguments(args);
        return dialog;
    }

    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        Activity activity = getActivity();
        return new AlertDialog.Builder(activity)
                .setMessage(getArguments().getString(ARG_MESSAGE))
                .setPositiveButton(android.R.string.ok,
                        (dialogInterface, i) -> activity.finish())
                .create();
    }
}
