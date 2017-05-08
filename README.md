Android + MQTT remote camera
============================

This is an Android camera app that connects to an MQTT server and lets you
remote-control it. Images are triggered by sending a "shutter" message and the
camera subsequently sends the image via MQTT.

Features
--------

* MQTT connectivity
* Remote trigger
* Battery & charge monitoring
* Focus-lock mode
* Screen dimming feature
* Multiple camera support

Usage
-----

Install the app and configure MQTT for your server/broker. Once the app is
running and MQTT is configured, MQTT will attempt to connect whenever the app
is open and will disconnect when navigated away.

MQTT Messages
-------------

When the app first starts, it generates a distinct UUID for that given device.
All messages will be sent under the topic `camera/{uuid}` where `{uuid}` is the
UUID of the camera. This ID can be configured in the settings if desired.

### Topic: `camera/{uuid}/status`

This represents the MQTT connectivity status of the camera.

Payload:

* `connected` - the app is running and connected to the MQTT broker
* `disconnected` - the app has disconnected from the MQTT broker

### Topic: `camera/{uuid}/shutter`

Send this message to trigger the shutter. Any payload will be ignored.

### Topic: `camera/{uuid}/image`

The payload of this message is the JPEG image taken by the camera.

### Topic: `camera/{uuid}/battery`

Payload:

A JSON object that looks like this:

```
{
  "charging": true,
  "percentage": 96,
  "plugType": "ac"
}
```

### Topic: `camera/{uuid}/focus`

When this message is received, the camera will re-focus the image.

### Topic: `camera/{uuid}/setting/auto_focus`

Payload:

* `1` (default) — the camera will attempt to re-focus the image each time the
  shutter is triggered
* `0` — the camera will not change focus when the shutter is triggered. This is
  meant to be used with the `focus` message.

### Topic: `camera/{uuid}/setting/dim_screen`

* `1` — the camera will dim the screen and disable the preview image to help
  save battery
* `0` (default) — the camera will show the preview image and leave the screen
  brightness at the default

Python Scripts
==============

In addition to the Android app, there are some handy Python scripts to
interface with the camera. All scripts are configured using a common JSON
config file. See `config.json.example` for an example. If SSL is not needed,
remove the `ca_certs` key.

`save_images.py`
----------------

This saves any images that are sent from any of the cameras. The output
directory is set using the `output_dir` key in the config file. The saved file
path is:

```
{output_dir}/{uuid}/{date_time}.jpg
```

Where `{uuid}` is the UUID of the camera and `{date_time}` is the date/time
that the image was received.

`timelapser.py`
---------------

This sends the `shutter` message at a periodic interval.

### MQTT Messages

#### `timelapser/status`

* `connected`
* `disconnected`

#### `timelapser/interval`

Payload is the number of seconds between triggers.

#### `timelapser/running`

Payload:
* `1` (default) - send this to start the intervalometer
* `0` - stop the intervalometer

