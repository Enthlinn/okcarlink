/*
 * lockdown_proto - the lockdown plist subset the wired CarPlay device side needs.
 *
 * Kept free of Android, BoringSSL and FunctionFS dependencies on purpose: this is the part of
 * okcar-ffs-usbmux that can be compiled and asserted on the host (the desktop harness for the ffs-usbmux sources), so the
 * request parsing and the reply bytes are verified without a device or a USB gadget.
 *
 * Wire framing (confirmed against liblockdown and the emulator's LockdownPlistChannel): a four-byte
 * big-endian length followed by UTF-8 XML.
 */
#ifndef OKCAR_LOCKDOWN_PROTO_H
#define OKCAR_LOCKDOWN_PROTO_H

#include <stddef.h>

/* One plist must fit; the emulator's StartService request is ~120 B. */
#define LOCKDOWN_MAX_PLIST 8192

/* The requests this daemon answers.  StartSession/StartService are the CarPlay path; GetValue and
 * SetValue are what the host sends FIRST, in plaintext, before StartSession -- the emulator opens the
 * lockdown connection with SetValue and, when it gets no reply, tears the connection down and
 * retries forever (measured 2026-10-06 on lmi: three identical retries, only "ignoring request
 * SetValue" in the log).  Ignoring them is therefore not a harmless simplification. */
#define LOCKDOWN_REQ_START_SESSION "StartSession"
#define LOCKDOWN_REQ_START_SERVICE "StartService"
#define LOCKDOWN_REQ_GET_VALUE     "GetValue"
#define LOCKDOWN_REQ_SET_VALUE     "SetValue"

/* The one service we can serve.  lockdownd maps it to the XPC name
 * "com.apple.carkit.service.lockdown", which carkitd registers (see
 * the CarKit reference 2.1).  The host dials the Port we return on the mux and speaks
 * raw iAP2 on it - no second TLS, because no service in lockdownd's table sets EnableServiceSSL. */
#define LOCKDOWN_SERVICE_CARKIT "com.apple.carkit.service"

/* Usbmux port the carkit byte pipe is served on.  Any unused number works: the value only has to be
 * the port the client dials next. */
#define LOCKDOWN_CARKIT_PORT 52316

/* Diagnostic services (the host asks for com.apple.syslog_relay first): answering InvalidService
 * made the emulator's whole attempt fail before it ever requested the carkit service, so these get their
 * own port where the daemon sends one line and closes -- its relay reader treats the empty read as
 * "stream ended" and the run continues. */
#define LOCKDOWN_DIAG_PORT 52317

typedef struct {
    char request[32];       /* <key>Request</key><string>...   ("" when absent) */
    char service[128];      /* <key>Service</key><string>...   ("" when absent) */
    char label[128];        /* <key>Label</key><string>...     ("" when absent) */
    char domain[64];        /* <key>Domain</key><string>...    (GetValue/SetValue) */
    char key[96];           /* <key>Key</key><string>...       (which setting) */
    int  has_value;         /* the request carried a <key>Value</key> entry (any type) */
} lockdown_request;

/* True when <key>key</key> is present at all, whatever the value type. */
int lockdown_plist_has_key(const char *plist, size_t len, const char *key);

/* True when the value of <key>key</key> (a <string>) was copied into out. */
int lockdown_plist_string(const char *plist, size_t len, const char *key, char *out, size_t out_len);

/* Parse a complete request plist.  Returns 1 when a Request value was found, 0 otherwise. */
int lockdown_parse(const char *plist, size_t len, lockdown_request *out);

/* Prefix xml with its four-byte big-endian length.  Returns the framed size, or 0 if it does not fit. */
size_t lockdown_frame(char *out, size_t out_len, const char *xml);

/* Reply builders: each returns the framed bytes ready for the TCP pipe (0 when it does not fit). */
size_t lockdown_reply_start_session(char *out, size_t out_len, const char *session_id);
size_t lockdown_reply_start_service(char *out, size_t out_len, const char *service, int port);
size_t lockdown_reply_error(char *out, size_t out_len, const char *request, const char *error);
/* Bare acknowledgement: <Request> only.  This is what a SetValue gets back. */
size_t lockdown_reply_ack(char *out, size_t out_len, const char *request);
/* GetValue answer.  value == NULL or "" means "no value for this key": the reply is still well
 * formed so the host does not stall, and the caller logs the key it could not answer. */
size_t lockdown_reply_value_string(char *out, size_t out_len, const char *key, const char *value);
/* Same, but the value is <data> already base64-encoded (DevicePublicKey is one of these and the
 * pairing client rejects anything that is not data). */
size_t lockdown_reply_value_data(char *out, size_t out_len, const char *key, const char *base64);

#endif /* OKCAR_LOCKDOWN_PROTO_H */
