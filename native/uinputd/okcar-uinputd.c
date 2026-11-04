/*
 * okcar-uinputd — root touch/key injection daemon for the 217 port target.
 *
 * The OkCarOS build injects through the hidden InputManager API; a normal phone
 * has no INJECT_EVENTS identity, so we create a virtual multitouch screen on
 * /dev/uinput and write raw input_event frames instead (plan §1, "uinput virtual
 * touchscreen" — the lowest-latency root path).
 *
 * Protocol: newline commands on stdin, one per event.
 *   D <slot> <x> <y>   touch down  (assigns a fresh tracking id)
 *   M <slot> <x> <y>   touch move
 *   U <slot>           touch up
 *   K <code> <0|1>     key up/down
 *   Q                  quit
 *
 * Coordinates are display pixels; the ABS ranges are set from --width/--height.
 * Companion idc: /system/usr/idc/okcar-uinput-touch.idc (touchScreen).
 *
 * Build (see native/uinputd/build.sh):
 *   aarch64-linux-android-gcc -static -O2 -o okcar-uinputd okcar-uinputd.c
 */
#include <errno.h>
#include <fcntl.h>
#include <linux/input.h>
#include <linux/uinput.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <unistd.h>

#define SLOTS 5

static int emit(int fd, __u16 type, __u16 code, int value) {
    struct input_event ev;
    memset(&ev, 0, sizeof(ev));
    gettimeofday(&ev.time, 0);
    ev.type = type;
    ev.code = code;
    ev.value = value;
    return write(fd, &ev, sizeof(ev)) == (ssize_t) sizeof(ev);
}

static int select_slot(int fd, int slot) {
    return emit(fd, EV_ABS, ABS_MT_SLOT, slot);
}

int main(int argc, char **argv) {
    int width = 1080, height = 2400;
    for (int i = 1; i < argc - 1; i++) {
        if (!strcmp(argv[i], "--width")) width = atoi(argv[i + 1]);
        if (!strcmp(argv[i], "--height")) height = atoi(argv[i + 1]);
    }

    int fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK);
    if (fd < 0) {
        perror("open /dev/uinput");
        return 1;
    }

    ioctl(fd, UI_SET_EVBIT, EV_KEY);
    ioctl(fd, UI_SET_KEYBIT, BTN_TOUCH);
    ioctl(fd, UI_SET_KEYBIT, KEY_BACK);
    ioctl(fd, UI_SET_KEYBIT, KEY_HOME);
    ioctl(fd, UI_SET_KEYBIT, KEY_APPSELECT);
    ioctl(fd, UI_SET_EVBIT, EV_ABS);

    ioctl(fd, UI_SET_ABSBIT, ABS_MT_SLOT);
    ioctl(fd, UI_SET_ABSBIT, ABS_MT_TRACKING_ID);
    ioctl(fd, UI_SET_ABSBIT, ABS_MT_POSITION_X);
    ioctl(fd, UI_SET_ABSBIT, ABS_MT_POSITION_Y);
    ioctl(fd, UI_SET_ABSBIT, ABS_MT_PRESSURE);

    struct uinput_abs_setup abs;
    memset(&abs, 0, sizeof(abs));

    abs.code = ABS_MT_SLOT;
    abs.absinfo.maximum = SLOTS - 1;
    ioctl(fd, UI_ABS_SETUP, &abs);

    abs.code = ABS_MT_TRACKING_ID;
    abs.absinfo.maximum = 0x7fffffff;
    ioctl(fd, UI_ABS_SETUP, &abs);

    abs.code = ABS_MT_POSITION_X;
    abs.absinfo.minimum = 0;
    abs.absinfo.maximum = width - 1;
    ioctl(fd, UI_ABS_SETUP, &abs);

    abs.code = ABS_MT_POSITION_Y;
    abs.absinfo.minimum = 0;
    abs.absinfo.maximum = height - 1;
    ioctl(fd, UI_ABS_SETUP, &abs);

    abs.code = ABS_MT_PRESSURE;
    abs.absinfo.maximum = 255;
    ioctl(fd, UI_ABS_SETUP, &abs);

    struct uinput_setup setup;
    memset(&setup, 0, sizeof(setup));
    setup.id.bustype = BUS_VIRTUAL;
    setup.id.vendor = 0x18d1;   /* Google-ish, harmless */
    setup.id.product = 0x0001;
    snprintf(setup.name, UINPUT_MAX_NAME_SIZE, "okcar-uinput-touch");
    ioctl(fd, UI_DEV_SETUP, &setup);
    ioctl(fd, UI_SET_PROPBIT, INPUT_PROP_DIRECT);
    if (ioctl(fd, UI_DEV_CREATE)) {
        perror("UI_DEV_CREATE");
        return 1;
    }
    usleep(300000); /* let InputFlinger pick the device up */

    int tracking = 1;
    int active[SLOTS];
    memset(active, 0, sizeof(active));

    char line[128];
    setvbuf(stdin, 0, _IOLBF, 0);
    while (fgets(line, sizeof(line), stdin)) {
        char op = line[0];
        int slot = 0, x = 0, y = 0, code = 0, value = 0;
        if (op == 'Q') {
            break;
        }
        if (op == 'K') {
            if (sscanf(line + 1, "%d %d", &code, &value) == 2) {
                emit(fd, EV_KEY, (__u16) code, value);
                emit(fd, EV_SYN, SYN_REPORT, 0);
            }
            continue;
        }
        if (op == 'D') {
            if (sscanf(line + 1, "%d %d %d", &slot, &x, &y) != 3) continue;
            if (slot < 0 || slot >= SLOTS) continue;
            select_slot(fd, slot);
            emit(fd, EV_ABS, ABS_MT_TRACKING_ID, tracking++);
            emit(fd, EV_ABS, ABS_MT_POSITION_X, x);
            emit(fd, EV_ABS, ABS_MT_POSITION_Y, y);
            emit(fd, EV_ABS, ABS_MT_PRESSURE, 100);
            if (!active[slot]) {
                emit(fd, EV_KEY, BTN_TOUCH, 1);
                active[slot] = 1;
            }
            emit(fd, EV_SYN, SYN_REPORT, 0);
            continue;
        }
        if (op == 'M') {
            if (sscanf(line + 1, "%d %d %d", &slot, &x, &y) != 3) continue;
            if (slot < 0 || slot >= SLOTS || !active[slot]) continue;
            select_slot(fd, slot);
            emit(fd, EV_ABS, ABS_MT_POSITION_X, x);
            emit(fd, EV_ABS, ABS_MT_POSITION_Y, y);
            emit(fd, EV_SYN, SYN_REPORT, 0);
            continue;
        }
        if (op == 'U') {
            if (sscanf(line + 1, "%d", &slot) != 1) continue;
            if (slot < 0 || slot >= SLOTS || !active[slot]) continue;
            select_slot(fd, slot);
            emit(fd, EV_ABS, ABS_MT_TRACKING_ID, -1);
            active[slot] = 0;
            int any = 0;
            for (int i = 0; i < SLOTS; i++) any |= active[i];
            if (!any) emit(fd, EV_KEY, BTN_TOUCH, 0);
            emit(fd, EV_SYN, SYN_REPORT, 0);
            continue;
        }
    }

    /* Release every finger before the device goes away.  A client that dies mid-gesture (measured
     * 2026-10-02: a SIGSEGV in the audio capture thread) otherwise leaves the pointer held in the
     * dispatcher, which ANRs whatever is on the screen — the phone's launcher went down with the
     * session.  EOF and "Q" both end up here. */
    for (int s = 0; s < SLOTS; s++) {
        if (!active[s]) continue;
        select_slot(fd, s);
        emit(fd, EV_ABS, ABS_MT_TRACKING_ID, -1);
        emit(fd, EV_KEY, BTN_TOUCH, 0);
        emit(fd, EV_SYN, SYN_REPORT, 0);
        active[s] = 0;
    }

    ioctl(fd, UI_DEV_DESTROY);
    close(fd);
    return 0;
}
