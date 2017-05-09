#!/usr/bin/env python3

import os
import sys

class TimelapseGen():
    def __init__(self, image_dir, start_file, end_file):
        self.image_dir = image_dir
        self.start_file = start_file
        self.end_file = end_file

    def frames(self):
        all_files = sorted(os.listdir(self.image_dir))
        subset = [f for f in all_files if self.start_file <= f <= self.end_file]

        return subset

    def make_links(self):
        for i, img in enumerate(self.frames()):
            symlink_name = os.path.join(self.image_dir, "anim{:08d}.jpg".format(i))
            source = os.path.join(self.image_dir, img)
            print("dest {} source {}".format(symlink_name, source))
            os.symlink(source, symlink_name)

def main():
    if len(sys.argv) < 4:
        print("""Usage: {} IMAGE_DIR START_FILE END_FILE

where START_FILE and END_FILE are in IMAGE_DIR and where START_FILE comes
before END_FILE chronologically / alphabetically.

From there you can do something like:

    ffmpeg -y -r 25 -i 'anim%08d.jpg' -s 1080x722 -r 40 -vcodec h264 -b:v 1024k anim.mp4

to generate a video from the stills.
                """.format(sys.argv[0]))
        sys.exit(1)
    lapse = TimelapseGen(sys.argv[1], sys.argv[2], sys.argv[3])
    lapse.make_links()

if __name__ == '__main__':
    main()
