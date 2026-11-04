/*
 * lockdown_proto - see lockdown_proto.h.  Host-compilable, no dependencies beyond libc.
 */
#include "lockdown_proto.h"

#include <stdio.h>
#include <string.h>

#define PLIST_HEAD \
    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" \
    "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" " \
    "\"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n" \
    "<plist version=\"1.0\"><dict>"

#define PLIST_TAIL "</dict></plist>"

int lockdown_plist_string(const char *plist, size_t len, const char *key, char *out, size_t out_len) {
    char open[64];
    const char *p, *end, *value;
    size_t key_len;

    if (!plist || !key || !out || out_len == 0) return 0;
    out[0] = '\0';
    if (snprintf(open, sizeof open, "<key>%s</key>", key) >= (int)sizeof open) return 0;
    key_len = strlen(open);
    if (key_len > len) return 0;

    end = plist + len;
    for (p = plist; (size_t)(end - p) >= key_len; p++) {
        if (memcmp(p, open, key_len) != 0) continue;
        p += key_len;
        /* Whitespace or attributes may sit between the key and the value element. */
        while (p < end && (*p == ' ' || *p == '\t' || *p == '\n' || *p == '\r')) p++;
        if ((size_t)(end - p) < 8 || memcmp(p, "<string>", 8) != 0) continue;
        value = p + 8;
        for (p = value; p < end && memcmp(p, "</string>", 9) != 0; p++) {
            size_t used = (size_t)(p - value);
            if (used + 1 >= out_len) return 0;          /* would truncate: treat as absent */
            out[used] = *p;
            out[used + 1] = '\0';
        }
        if (p >= end) { out[0] = '\0'; return 0; }      /* unterminated <string> */
        return 1;
    }
    return 0;
}

/* Local byte search: memmem is a GNU/bionic extension and this file must also build with a plain
 * host libc for the assertions in run.sh. */
static const char *find_bytes(const char *hay, size_t hay_len, const char *needle) {
    size_t nlen = strlen(needle), i;
    if (!nlen || nlen > hay_len) return NULL;
    for (i = 0; i + nlen <= hay_len; i++) {
        if (memcmp(hay + i, needle, nlen) == 0) return hay + i;
    }
    return NULL;
}

int lockdown_plist_has_key(const char *plist, size_t len, const char *key) {
    char needle[128];
    if (!plist || !key) return 0;
    snprintf(needle, sizeof needle, "<key>%s</key>", key);
    return find_bytes(plist, len, needle) != NULL;
}

int lockdown_parse(const char *plist, size_t len, lockdown_request *out) {
    if (!plist || !out) return 0;
    memset(out, 0, sizeof *out);
    lockdown_plist_string(plist, len, "Request", out->request, sizeof out->request);
    lockdown_plist_string(plist, len, "Service", out->service, sizeof out->service);
    lockdown_plist_string(plist, len, "Label", out->label, sizeof out->label);
    lockdown_plist_string(plist, len, "Domain", out->domain, sizeof out->domain);
    lockdown_plist_string(plist, len, "Key", out->key, sizeof out->key);
    out->has_value = lockdown_plist_has_key(plist, len, "Value");
    return out->request[0] != '\0';
}

size_t lockdown_frame(char *out, size_t out_len, const char *xml) {
    size_t body;
    if (!out || !xml) return 0;
    body = strlen(xml);
    if (body + 4 > out_len || body > 0xffffffffu) return 0;
    out[0] = (char)((body >> 24) & 0xff);
    out[1] = (char)((body >> 16) & 0xff);
    out[2] = (char)((body >> 8) & 0xff);
    out[3] = (char)(body & 0xff);
    memcpy(out + 4, xml, body);
    return body + 4;
}

static size_t reply(char *out, size_t out_len, const char *xml) {
    return lockdown_frame(out, out_len, xml);
}

size_t lockdown_reply_start_session(char *out, size_t out_len, const char *session_id) {
    char xml[512];
    if (!session_id) session_id = "OKCARLOCKDOWN0001";
    snprintf(xml, sizeof xml,
             PLIST_HEAD
             "<key>Request</key><string>" LOCKDOWN_REQ_START_SESSION "</string>"
             "<key>EnableSessionSSL</key><true/>"
             "<key>SessionID</key><string>%s</string>"
             PLIST_TAIL, session_id);
    return reply(out, out_len, xml);
}

size_t lockdown_reply_start_service(char *out, size_t out_len, const char *service, int port) {
    char xml[512];
    if (!service) service = LOCKDOWN_SERVICE_CARKIT;
    snprintf(xml, sizeof xml,
             PLIST_HEAD
             "<key>Request</key><string>" LOCKDOWN_REQ_START_SERVICE "</string>"
             "<key>Service</key><string>%s</string>"
             "<key>Port</key><integer>%d</integer>"
             PLIST_TAIL, service, port);
    return reply(out, out_len, xml);
}

size_t lockdown_reply_ack(char *out, size_t out_len, const char *request) {
    char xml[256];
    if (!request) request = "";
    snprintf(xml, sizeof xml,
             PLIST_HEAD
             "<key>Request</key><string>%s</string>"
             PLIST_TAIL, request);
    return reply(out, out_len, xml);
}

size_t lockdown_reply_value_string(char *out, size_t out_len, const char *key, const char *value) {
    char xml[384];
    if (!key) key = "";
    if (!value) value = "";
    snprintf(xml, sizeof xml,
             PLIST_HEAD
             "<key>Request</key><string>" LOCKDOWN_REQ_GET_VALUE "</string>"
             "<key>Key</key><string>%s</string>"
             "<key>Value</key><string>%s</string>"
             PLIST_TAIL, key, value);
    return reply(out, out_len, xml);
}

size_t lockdown_reply_value_data(char *out, size_t out_len, const char *key, const char *base64) {
    char xml[2048];
    if (!key) key = "";
    if (!base64) base64 = "";
    snprintf(xml, sizeof xml,
             PLIST_HEAD
             "<key>Request</key><string>" LOCKDOWN_REQ_GET_VALUE "</string>"
             "<key>Key</key><string>%s</string>"
             "<key>Value</key><data>%s</data>"
             PLIST_TAIL, key, base64);
    return reply(out, out_len, xml);
}

size_t lockdown_reply_error(char *out, size_t out_len, const char *request, const char *error) {
    char xml[512];
    if (!request) request = "";
    if (!error) error = "InvalidService";
    snprintf(xml, sizeof xml,
             PLIST_HEAD
             "<key>Request</key><string>%s</string>"
             "<key>Error</key><string>%s</string>"
             PLIST_TAIL, request, error);
    return reply(out, out_len, xml);
}
