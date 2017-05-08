"""Create timelapses"""

import os
import sys
import time
from threading import Thread
from sched import scheduler
from mqtt_base import MQTTBase

class Timelapser(MQTTBase):
    delay = 60
    lapse_thread = None

    def __init__(self, config_file):
        MQTTBase.__init__(self, config_file=config_file)

    def on_connect(self, client, userdata, flags, conn_result):
        self.mqtt.subscribe('timelapser/#')
        self.mqtt.publish('timelapser/status', 'connected', 0, True)
        print("Connected.")

    def on_message(self, client, userdata, message):
        parts = message.topic.split('/')
        if parts[1] == 'interval':
            try:
                new_delay = int(message.payload.decode('utf-8'))
                if new_delay > 0:
                    self.delay = new_delay
                    print("Set delay to {:d}s".format(self.delay))
            except ValueError as err:
                print("Invalid delay: {}".format(err))
        elif parts[1] == 'running':
            try:
                is_running = int(message.payload.decode('utf-8'))
                self.set_running(bool(is_running))
            except ValueError as err:
                print("Invalid running: {}".format(err))

    def loop(self):
        thread = Thread(target=self.mqtt.loop_forever)
        thread.start()
        self.start_lapse()

    def set_running(self, running):
        if self.is_running() == running:
            return
        if running:
            self.start_lapse()
        else:
            self.stop_lapse()

    def is_running(self):
        return self.lapse_thread is not None

    def start_lapse(self):
        if not self.is_running():
            self.lapse_thread = Thread(target=self.lapse_loop)
            self.lapse_thread.start()
            print("Intervalometer started.")

    def stop_lapse(self):
        if self.is_running():
            self.lapse_thread = None

    def trigger(self):
        self.mqtt.publish('camera/f01d25b1-a5aa-4ef1-a643-87b14c9b2ca9/shutter')

    def lapse_loop(self):
        while self.is_running():
            self.trigger()
            time.sleep(self.delay)
        print("Intervalometer stopped.")

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
