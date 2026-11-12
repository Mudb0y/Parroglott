package com.ivona.ttslib.ics;

/**
 * Not a service. Amazon's engine looks this class up by name as it loads, to
 * borrow the app's class loader, and refuses to load when it is missing.
 */
public final class IVONATextToSpeechService {
    private IVONATextToSpeechService() {
    }
}
