# Three non-web schemes: one owned and accepted, one owned and declined, one
# owned by nothing at all.
steps() {
    # 1. tel: resolves to the dialer, and the handoff is carried through.
    tap 540 745;  sleep 3
    btn Open;     sleep 6

    herald; sleep 4

    # 2. mailto: resolves to a different package, and is declined, because the
    #    prompt is the point rather than the mail app.
    tap 540 1000; sleep 3
    btn Cancel;   sleep 3

    # 3. whatsapp: resolves to nothing here, so no prompt appears at all — the
    #    query decides, and an unowned scheme is not guessed at.
    tap 540 1255; sleep 5
}
