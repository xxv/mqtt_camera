"""MQTTBase"""

import ssl
import json

import paho.mqtt.client as paho

class MQTTBase():
    def __init__(self, config_file=None, config=None):
        if config_file:
            self.mqtt_config = self.read_config(config_file)
        elif config:
            self.mqtt_config = config
        else:
            raise Exception("must provide a configuration")
        self.mqtt = paho.Client()
        self.mqtt.on_connect = self.on_connect
        self.mqtt.on_message = self.on_message
        self.mqtt.on_disconnect = self.on_disconnect

    def connect(self):
        print("Connecting to {host}:{port}...".format(**self.mqtt_config))
        if 'ca_certs' in self.mqtt_config:
            self.mqtt.tls_set(self.mqtt_config['ca_certs'], tls_version=ssl.PROTOCOL_TLSv1_2)

        if 'user' in self.mqtt_config:
            self.mqtt.username_pw_set(self.mqtt_config['user'], self.mqtt_config['password'])
        self.mqtt.connect(self.mqtt_config['host'], self.mqtt_config['port'])

    def loop(self):
        self.mqtt.loop_forever()

    def on_connect(self, client, userdata, flags, conn_result):
        pass

    def on_message(self, client, userdata, message):
        pass

    def on_disconnect(self, client, userdata, flags):
        print(flags)
        print("Disconnected")

    def read_config(self, config_file_name):
        config = None
        with open(config_file_name) as config_file:
            config = json.load(config_file)

        return config

    def disconnect(self):
        self.mqtt.disconnect()
