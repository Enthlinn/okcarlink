/*
 * Host assertions for lockdown_proto (the desktop harness for the ffs-usbmux sources).
 *
 * The point of these cases is that the daemon cannot be exercised without a USB gadget and a host
 * running the emulator, so the bytes we answer with are pinned here instead: the StartSession reply that
 * the emulator demands, the StartService reply with an integer Port, and the unknown-service error.
 */
#include "lockdown_proto.h"

#include <stdio.h>
#include <string.h>

static int g_checks = 0;
static int g_failed = 0;

static void check(const char *what, int ok, const char *detail) {
    g_checks++;
    if (ok) {
        printf("  PASS  %s\n", what);
    } else {
        printf("  FAIL  %s  %s\n", what, detail ? detail : "");
        g_failed++;
    }
}

/* The exact request the emulator's LockdownCarKitClient.openService() sends. */
static const char *START_SESSION_REQ =
    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
    "<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" "
    "\"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n"
    "<plist version=\"1.0\"><dict>"
    "<key>Label</key><string>the emulator</string>"
    "<key>Request</key><string>StartSession</string>"
    "<key>HostID</key><string>0E1E2E3E4E5E</string>"
    "<key>SystemBUID</key><string>ABCD-1234</string>"
    "</dict></plist>";

static const char *START_SERVICE_REQ =
    "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
    "<plist version=\"1.0\"><dict>"
    "<key>Request</key><string>StartService</string>"
    "<key>Service</key><string>com.apple.carkit.service</string>"
    "</dict></plist>";

int main(void) {
    char buf[LOCKDOWN_MAX_PLIST];
    char value[256];
    size_t n = 0;
    lockdown_request req;

    printf("lockdown proto suite\n");

    /* 1. request parsing */
    check("StartSession parsed",
          lockdown_parse(START_SESSION_REQ, strlen(START_SESSION_REQ), &req) &&
              strcmp(req.request, "StartSession") == 0,
          req.request);
    check("StartSession Label read",
          strcmp(req.label, "the emulator") == 0, req.label);
    check("StartService parsed",
          lockdown_parse(START_SERVICE_REQ, strlen(START_SERVICE_REQ), &req) &&
              strcmp(req.request, "StartService") == 0,
          req.request);
    check("StartService Service read",
          strcmp(req.service, LOCKDOWN_SERVICE_CARKIT) == 0, req.service);

    /* 2. framing: four-byte big-endian length, then the plist verbatim */
    n = lockdown_frame(buf, sizeof buf, "<plist/>");
    check("frame length prefix is big-endian",
          n == 12 && (unsigned char)buf[0] == 0 && (unsigned char)buf[1] == 0 &&
              (unsigned char)buf[2] == 0 && (unsigned char)buf[3] == 8 &&
              memcmp(buf + 4, "<plist/>", 8) == 0,
          "bad prefix");
    check("frame refuses a body that does not fit",
          lockdown_frame(buf, 8, "<plist/>") == 0, "expected 0");

    /* 3. StartSession reply - the emulator throws without EnableSessionSSL=true */
    n = lockdown_reply_start_session(buf, sizeof buf, "OKCARLOCKDOWN0001");
    check("StartSession reply is framed",
          n > 4 && (size_t)(((unsigned char)buf[0] << 24) | ((unsigned char)buf[1] << 16) |
                            ((unsigned char)buf[2] << 8) | (unsigned char)buf[3]) == n - 4,
          "length mismatch");
    buf[n] = '\0';
    check("StartSession reply enables TLS",
          strstr(buf + 4, "<key>EnableSessionSSL</key><true/>") != NULL, buf + 4);
    check("StartSession reply carries the session id",
          strstr(buf + 4, "<string>OKCARLOCKDOWN0001</string>") != NULL, buf + 4);

    /* 4. StartService reply - the emulator reads an integer Port and defaults EnableServiceSSL to false */
    n = lockdown_reply_start_service(buf, sizeof buf, LOCKDOWN_SERVICE_CARKIT, LOCKDOWN_CARKIT_PORT);
    buf[n] = '\0';
    check("StartService reply echoes the service",
          strstr(buf + 4, "<key>Service</key><string>com.apple.carkit.service</string>") != NULL,
          buf + 4);
    check("StartService reply carries an integer Port",
          strstr(buf + 4, "<key>Port</key><integer>52316</integer>") != NULL, buf + 4);
    check("StartService reply omits EnableServiceSSL",
          strstr(buf + 4, "EnableServiceSSL") == NULL, buf + 4);
    check("StartService reply parses back as a request",
          lockdown_parse(buf + 4, n - 4, &req) && strcmp(req.service, LOCKDOWN_SERVICE_CARKIT) == 0,
          req.service);

    /* 5. unknown service -> Error, which is what the emulator's rejectError() looks for */
    n = lockdown_reply_error(buf, sizeof buf, LOCKDOWN_REQ_START_SERVICE, "InvalidService");
    buf[n] = '\0';
    check("error reply carries Error=InvalidService",
          strstr(buf + 4, "<key>Error</key><string>InvalidService</string>") != NULL, buf + 4);

    /* 6. malformed input must not be accepted (and must not walk off the buffer) */
    check("truncated plist rejected",
          lockdown_parse("<plist><dict><key>Request</key><string>StartSes", 48, &req) == 0,
          "accepted a truncated value");
    check("missing Request rejected",
          lockdown_parse("<plist><dict><key>Service</key><string>x</string></dict></plist>",
                         strlen("<plist><dict><key>Service</key><string>x</string></dict></plist>"),
                         &req) == 0,
          "accepted a request without Request");
    check("short key does not match a longer one",
          lockdown_plist_string("<key>RequestX</key><string>no</string>", 39, "Request",
                                value, sizeof value) == 0,
          value);
    check("attributes after the key are tolerated",
          lockdown_plist_string("<key>Request</key> <string>StartSession</string>", 48, "Request",
                                value, sizeof value) && strcmp(value, "StartSession") == 0,
          value);


    /* GetValue/SetValue: the emulator opens lockdown with a plaintext SetValue and waits for the reply. */
    {
        const char *sv = "<plist><dict>"
                         "<key>Label</key><string>diplay</string>"
                         "<key>Request</key><string>SetValue</string>"
                         "<key>Domain</key><string>com.apple.mobile.lockdown</string>"
                         "<key>Key</key><string>SomeKey</string>"
                         "<key>Value</key><integer>1</integer>"
                         "</dict></plist>";
        lockdown_request r;
        char reply[512];
        size_t n;
        check("SetValue parses Domain/Key/Value",
              lockdown_parse(sv, strlen(sv), &r) && strcmp(r.request, "SetValue") == 0
              && strcmp(r.domain, "com.apple.mobile.lockdown") == 0 && strcmp(r.key, "SomeKey") == 0
              && r.has_value == 1,
              r.key);
        n = lockdown_reply_ack(reply, sizeof reply, "SetValue");
        check("SetValue is acknowledged with a framed plist",
              n > 4 && reply[3] != 0 && strstr(reply + 4, "<string>SetValue</string>") != NULL,
              reply + 4);
        n = lockdown_reply_value_string(reply, sizeof reply, "ProductType", "iPhone10,1");
        check("GetValue answer carries Key and Value",
              n > 4 && strstr(reply + 4, "<key>Key</key><string>ProductType</string>") != NULL
              && strstr(reply + 4, "<value>") == NULL
              && strstr(reply + 4, "<key>Value</key><string>iPhone10,1</string>") != NULL,
              reply + 4);
        check("GetValue with no known value still answers well formed",
              lockdown_reply_value_string(reply, sizeof reply, "UniqueDeviceID", "") > 4
              && strstr(reply + 4, "<key>Value</key><string></string>") != NULL,
              reply + 4);
    }

    if (g_failed == 0) {
        printf("LOCKDOWN PROTO SUITE OK (%d checks)\n", g_checks);
        return 0;
    }
    printf("LOCKDOWN PROTO SUITE FAILED (%d/%d)\n", g_failed, g_checks);
    return 1;
}
