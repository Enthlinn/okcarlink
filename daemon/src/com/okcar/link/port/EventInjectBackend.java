package com.okcar.link.port;

import android.view.InputEvent;

/**
 * Privileged input-event sink. The OkCarOS build injects through the hidden
 * {@code InputManager.injectInputEvent} (needs INJECT_EVENTS + platform signature);
 * a normal-phone build installs an alternative here (uinput virtual touch device
 * or a shell-uid InputManager bridge — see the port design notes).
 *
 * <p>Pure delegation: implementations own nothing but the final privileged call.
 */
public interface EventInjectBackend {

    /**
     * Injects one input event asynchronously (the classic ASYNC mode).
     *
     * @return true when the platform accepted the event
     */
    boolean injectInputEvent(InputEvent event);
}
