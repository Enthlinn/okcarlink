# Touch classification for the okcar-uinputd virtual device (plan §1).
# Without this Android treats the device as a generic pointer and the mirrored
# taps land with the wrong semantics.
touch.deviceType = touchScreen
touch.orientationAware = 1
keyboard.layout = okcar-uinput-touch
