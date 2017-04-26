"""Create timelapses"""

import os
import sys
import time
from threading import Thread
from sched import scheduler
from mqtt_base import MQTTBase

class Timelapser(MQTTBase):
    def __init__(self, config_file):
        MQTTBase.__init__(self, config_file=config_file)
        self.delay = 60

    def on_connect(self, client, userdata, flags, conn_result):
        self.mqtt.subscribe('timelapser/#')
        self.mqtt.publish('timelapser/status', 'connected', 0, True)
        print("Connected.")

    def on_message(self, client, userdata, message):
        parts = message.topic.split('/')

    def loop(self):
        thread = Thread(target=self.mqtt.loop_forever)
        thread.start()

        lapse_thread = Thread(target=self.lapse_loop)
        lapse_thread.start()

    def trigger(self):
        self.mqtt.publish('camera/f01d25b1-a5aa-4ef1-a643-87b14c9b2ca9/shutter')

    def lapse_loop(self):
        while True:
            self.trigger()
            time.sleep(self.delay)

def main():
    if len(sys.argv) < 2:
        print("Usage: {} config.json".format(sys.argv[0]))
        sys.exit(1)

    timelapser = Timelapser(sys.argv[1])
    timelapser.mqtt.will_set('timelapser/status', 'disconnected', 0, True)
    timelapser.connect()
    timelapser.loop()

if __name__ == '__main__':
    main()
