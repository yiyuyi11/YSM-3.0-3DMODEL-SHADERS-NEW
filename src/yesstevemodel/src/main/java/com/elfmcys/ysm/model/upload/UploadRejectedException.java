package com.elfmcys.ysm.model.upload;

/** An upload refused for a reason the uploader can read and fix; the message is shown to them verbatim. */
public final class UploadRejectedException extends Exception {
    public UploadRejectedException(String message) {
        super(message);
    }

    public UploadRejectedException(String message, Throwable cause) {
        super(message, cause);
    }
}
