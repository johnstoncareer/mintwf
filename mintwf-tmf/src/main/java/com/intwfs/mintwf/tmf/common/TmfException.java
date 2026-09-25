package com.intwfs.mintwf.tmf.common;

import org.springframework.http.HttpStatus;

/**
 * An error returned to the client as a TMF {@code Error} body.
 */
public class TmfException extends RuntimeException {

    private final HttpStatus status;

    public TmfException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }

    public static TmfException notFound(String resource, String id) {
        return new TmfException(HttpStatus.NOT_FOUND, "No " + resource + " found with id '" + id + "'");
    }

    public static TmfException badRequest(String message) {
        return new TmfException(HttpStatus.BAD_REQUEST, message);
    }
}
