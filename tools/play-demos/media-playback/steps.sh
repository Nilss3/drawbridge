# Page audio the user started, still playing with herald off screen.
prelaunch() {
    # A completed download from another take would sit in the same shade.
    $ADB shell pm clear $PKG >/dev/null
}

steps() {
    # Playback begins on a press. Nothing on this page plays by itself.
    tap 540 820; sleep 6

    # Off screen, still playing: the notification is what is left of the page.
    home;  sleep 3
    shade; sleep 6

    # And the controls in it are real controls, not a label. By coordinate
    # rather than by label, unlike every other button these scripts press:
    # SystemUI draws the media panel in a way uiautomator's dump does not
    # describe, so there is no node to look up. The shade is in a known state —
    # app data was just cleared, so the panel is the only thing in it.
    tap 932 737; sleep 3
    unshade;   sleep 1

    # Back in the page: the clock ran on while the browser was not on screen,
    # and stopped where the notification stopped it.
    herald; sleep 6
}
