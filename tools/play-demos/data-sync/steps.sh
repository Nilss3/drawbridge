# A download the user asked for, which survives the browser leaving the screen.
prelaunch() {
    # Previous takes leave finished downloads in the list and completed
    # notifications in the shade, both of which end up on camera.
    $ADB shell pm clear $PKG >/dev/null
    $ADB shell rm -f /sdcard/Download/herald-sample-file.bin
}

steps() {
    # Nothing downloads until the link is tapped and the prompt is confirmed.
    tap 540 912;   sleep 3
    btn Download;  sleep 2

    # Off screen is the case the service exists for, so the video goes there
    # rather than watching the progress from inside the browser.
    home;  sleep 2
    shade; sleep 8      # running, counting up
    sleep 7             # and finished, in the same open shade
    unshade; sleep 1

    # The file is where the browser says it is.
    herald; sleep 3
    tap 1032 139; sleep 2
    btn Downloads; sleep 5
}
