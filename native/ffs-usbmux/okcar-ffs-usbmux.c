/*
 * okcar-ffs-usbmux - the device side of wired CarPlay for 217.
 *
 * One static arm64 binary that, together with a configfs gadget, makes 217 look like an iPhone to a
 * host (the emulator on lmi, or a real head unit that acts as USB host):
 *
 *   1. FunctionFS descriptors: the Apple USBMUX interface (class 0xff / subclass 0xfe /
 *      protocol 0x02) with two bulk endpoints, exactly what IphoneCarPlayConfiguration.find() wants
 *      next to the CDC-NCM pair that configfs' ncm function contributes.
 *   2. The vendor request the emulator sends to request the CarPlay configuration
 *      (controlTransfer(0xc0, 0x52, 0, 4, buf[1], 1)) answered on EP0.
 *   3. Apple's USB mux protocol over those bulk endpoints: big-endian 16-byte frames
 *      (protocol, length, magic, sequence, acknowledgement), the version handshake, and the
 *      TCP-over-usbmux connection handling the lockdown client uses.
 *   4. Lockdown: the plaintext StartSession plist answered with EnableSessionSSL=true, then a TLS
 *      server driven through a BoringSSL BIO pair.
 *   5. StartService com.apple.carkit.service answered with a Port and no EnableServiceSSL, so the
 *      host does not run a second TLS handshake.  The connection it then dials on that port is a raw
 *      iAP2 byte pipe (the host opens with the 6-byte marker + SYN); it is bridged to the Android
 *      abstract socket "okcar-carkit", where the Java side owns the iAP2 device role.
 *
 * Build: native/ffs-usbmux/build.sh (static aarch64 bionic + static BoringSSL from the same tree).
 * Run:   okcar-ffs-usbmux /dev/usb-ffs/okcar/ep0
 */
#include <errno.h>
#include <fcntl.h>
#include <stddef.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <time.h>
#include <sys/un.h>
#include <sys/stat.h>
#include <unistd.h>
#include <pthread.h>
#include <openssl/ssl.h>
#include <openssl/bio.h>
#include <openssl/err.h>
#include <openssl/evp.h>
#include <openssl/pem.h>
#include <openssl/x509.h>
#include <openssl/rsa.h>
#include <openssl/bn.h>
#include <openssl/rand.h>

#include "lockdown_proto.h"

/* --- FunctionFS ABI (copied so header drift cannot break the binary) --- */
#define FFS_MAGIC_V2 3u
#define FFS_FLAG_HAS_FS_DESC 1u
#define FFS_FLAG_HAS_HS_DESC 2u
#define FFS_FLAG_ALL_CTRL_RECIP 64u

#define FFS_BIND 0
#define FFS_UNBIND 1
#define FFS_ENABLE 2
#define FFS_DISABLE 3
#define FFS_SETUP 4
#define FFS_SUSPEND 5
#define FFS_RESUME 6

struct ffs_descs_head_v2 {
    uint32_t magic;
    uint32_t length;
    uint32_t flags;
} __attribute__((packed));

struct usb_ctrlrequest {
    uint8_t bRequestType;
    uint8_t bRequest;
    uint16_t wValue;
    uint16_t wIndex;
    uint16_t wLength;
} __attribute__((packed));

struct ffs_event {
    struct usb_ctrlrequest setup;
    uint8_t type;
    uint8_t pad[3];
} __attribute__((packed));

/* --- our descriptors: the iPhone's USBMUX interface --- */
#define USBMUX_CLASS 0xff
#define USBMUX_SUBCLASS 0xfe
#define USBMUX_PROTOCOL 0x02
#define EP_OUT 0x04
#define EP_IN 0x85

#define MUX_HEADER_BYTES 16
#define MUX_MAX_FRAME 65536
#define TCP_MAX_PAYLOAD 4096
#define TCP_HEADER_BYTES 20   /* usbmux TCP header: sport, dport, seq, ack, offset, flags, window */
#define PROTO_VERSION 0
#define PROTO_RESULT 1
#define PROTO_TCP 6
#define USBMUX_VERSION 2
#define VERSION_MESSAGE_BYTES 20
#define LOCKDOWN_PORT 62078

/* Device public key served for GetValue DevicePublicKey: the base64 of a PKCS#1 RSA public-key PEM,
 * which the host decodes into its pair record.  No key material ships with this repository.
 * Resolution order:
 *   1. DEVICE_PUBKEY_PKCS1_PEM_B64, when the builder compiled one in (kept for compatibility);
 *   2. the public half of the certificate installed at LOCKDOWN_CERT -- the normal path, and the
 *      one that keeps the advertised key and the TLS certificate this same binary presents in
 *      agreement (a mismatch is what makes a host keep re-asking for pairing; docs/crypto.md);
 *   3. nothing: the reply is an empty <data> and the warning below is logged. */
static const char DEVICE_PUBKEY_PKCS1_PEM_B64[] =
    "";
#define LOCKDOWN_CERT "/data/local/tmp/lockdown-cert.pem"
#define LOCKDOWN_KEY "/data/local/tmp/lockdown-key.pem"
#define IDENTITY_CN "iPhone"

/* Base64 of the PKCS#1 public-key PEM read from the installed lockdown certificate.
 * Writes '\0' to out[0] and returns 0 when no usable certificate is present. */
static int device_pubkey_from_cert(char *out, size_t out_len)
{
    FILE *f;
    X509 *cert;
    EVP_PKEY *pk;
    unsigned char der[1024];
    unsigned char *p = der;
    char b64[1024];
    char pem[2048];
    int dl, pl, need;

    out[0] = '\0';
    f = fopen(LOCKDOWN_CERT, "r");
    if (!f) return 0;
    cert = PEM_read_X509(f, NULL, NULL, NULL);
    fclose(f);
    if (!cert) return 0;
    pk = X509_get_pubkey(cert);
    X509_free(cert);
    if (!pk) return 0;
    dl = i2d_PublicKey(pk, NULL);
    if (dl <= 0 || (size_t)dl + 1 > sizeof der) { EVP_PKEY_free(pk); return 0; }
    if (i2d_PublicKey(pk, &p) != dl) { EVP_PKEY_free(pk); return 0; }
    EVP_PKEY_free(pk);
    if (EVP_EncodeBlock((unsigned char *)b64, der, dl) <= 0) return 0;
    pl = snprintf(pem, sizeof pem,
                  "-----BEGIN RSA PUBLIC KEY-----\n%s\n-----END RSA PUBLIC KEY-----\n", b64);
    if (pl <= 0 || (size_t)pl >= sizeof pem) return 0;
    /* The plist <data> payload is the base64 of that PEM text. */
    need = 4 * ((pl + 2) / 3) + 1;
    if ((size_t)need > out_len) return 0;
    if (EVP_EncodeBlock((unsigned char *)out, (const unsigned char *)pem, (size_t)pl) <= 0) return 0;
    return 1;
}

/* --- lockdown identity generation ------------------------------------------------------------
 * A wired session cannot complete EnableSessionSSL without a certificate pair, and no key
 * material ships with this repository.  Rather than making every builder run openssl, the
 * binary that needs the pair can make one: BoringSSL is already linked in for TLS, so
 * RSA + a self-signed X509 costs nothing extra.
 *     okcar-ffs-usbmux --gen-identity [--force]
 * Shape kept compatible with the pairs hosts have accepted so far: CN=iPhone, RSA-2048,
 * SHA-256 with RSA, ten years, self-signed.  Written where device_pubkey_from_cert() and
 * tls_begin() look for them (LOCKDOWN_CERT / LOCKDOWN_KEY); the key is 0600, the cert 0644.
 * Exit: 0 generated or already present, 1 failure. */

static int identity_write(const char *path, const char *data, size_t len, mode_t mode)
{
    int fd = open(path, O_WRONLY | O_CREAT | O_TRUNC, mode);
    size_t off = 0;
    if (fd < 0) {
        fprintf(stderr, "identity: cannot write %s (errno=%d)\n", path, errno);
        return 0;
    }
    while (off < len) {
        ssize_t n = write(fd, data + off, len - off);
        if (n <= 0) {
            fprintf(stderr, "identity: write %s failed (errno=%d)\n", path, errno);
            close(fd);
            return 0;
        }
        off += (size_t)n;
    }
    fchmod(fd, mode);
    close(fd);
    return 1;
}

static int gen_identity(int force)
{
    EVP_PKEY *pkey = NULL;
    RSA *rsa = NULL;
    BIGNUM *e = NULL;
    X509 *cert = NULL;
    X509_NAME *name = NULL;
    BIO *kbio = NULL, *cbio = NULL;
    char *kptr = NULL, *cptr = NULL;
    long klen, clen;
    int rc = -1;

    if (!force && access(LOCKDOWN_CERT, R_OK) == 0 && access(LOCKDOWN_KEY, R_OK) == 0) {
        fprintf(stderr, "identity: %s already present (pass --force to replace it)\n", LOCKDOWN_CERT);
        return 0;
    }
    fprintf(stderr, "identity: generating a self-signed RSA-2048 certificate (CN=%s)\n", IDENTITY_CN);

    pkey = EVP_PKEY_new();
    rsa = RSA_new();
    e = BN_new();
    if (!pkey || !rsa || !e) goto done;
    if (!BN_set_word(e, RSA_F4)) goto done;
    if (!RSA_generate_key_ex(rsa, 2048, e, NULL)) goto done;
    if (!EVP_PKEY_assign_RSA(pkey, rsa)) goto done;
    rsa = NULL;                        /* owned by pkey from here on */

    cert = X509_new();
    if (!cert) goto done;
    if (!X509_set_version(cert, 2)) goto done;                    /* v3 */
    if (!ASN1_INTEGER_set(X509_get_serialNumber(cert), (long)time(NULL))) goto done;
    if (!X509_gmtime_adj(X509_getm_notBefore(cert), -3600L)) goto done;   /* one hour of clock skew */
    if (!X509_gmtime_adj(X509_getm_notAfter(cert), 10L * 365 * 24 * 3600)) goto done;
    name = X509_get_subject_name(cert);
    if (!name) goto done;
    if (!X509_NAME_add_entry_by_txt(name, "CN", MBSTRING_ASC,
                                    (const unsigned char *)IDENTITY_CN, -1, -1, 0)) goto done;
    if (!X509_set_issuer_name(cert, name)) goto done;             /* self-signed */
    if (!X509_set_pubkey(cert, pkey)) goto done;
    if (!X509_sign(cert, pkey, EVP_sha256())) goto done;

    kbio = BIO_new(BIO_s_mem());
    cbio = BIO_new(BIO_s_mem());
    if (!kbio || !cbio) goto done;
    if (!PEM_write_bio_PrivateKey(kbio, pkey, NULL, NULL, 0, NULL, NULL)) goto done;
    if (!PEM_write_bio_X509(cbio, cert)) goto done;
    klen = BIO_get_mem_data(kbio, &kptr);
    clen = BIO_get_mem_data(cbio, &cptr);
    if (klen <= 0 || clen <= 0) goto done;
    if (!identity_write(LOCKDOWN_KEY, kptr, (size_t)klen, 0600)) goto done;
    if (!identity_write(LOCKDOWN_CERT, cptr, (size_t)clen, 0644)) goto done;
    fprintf(stderr, "identity: wrote %s (0600) and %s (0644)\n", LOCKDOWN_KEY, LOCKDOWN_CERT);
    rc = 1;

done:
    if (rc != 1) fprintf(stderr, "identity: generation failed\n");
    if (kbio) BIO_free(kbio);
    if (cbio) BIO_free(cbio);
    if (cert) X509_free(cert);
    if (pkey) EVP_PKEY_free(pkey);
    if (rsa) RSA_free(rsa);
    if (e) BN_free(e);
    return rc;
}

#define FFS_EP_OUT "/dev/usb-ffs/okcar/ep1"
#define FFS_EP_IN "/dev/usb-ffs/okcar/ep2"

/* Android abstract-namespace socket the Java side listens on (LocalServerSocket(name)); the daemon
 * connects to it when the host dials the carkit service port. */
#define CARKIT_SOCKET_NAME "okcar-carkit"

/* --- usbmux connections: the host keeps the lockdown connection open while it uses the service
 *     connection, so one slot per connection is required (the single-connection model used to tear
 *     the lockdown session down as soon as the service port was dialled).
 *
 *     Sizing: one bring-up needs lockdown (62078), the syslog relay port we hand out, and the
 *     carkit service port -- three, four when the host re-pairs.  the emulator retries the whole bring-up
 *     up to six times without ever disabling the endpoints, so a slot that is not released on FIN
 *     accumulates one per attempt and the carkit port is the first to be refused (measured
 *     2026-10-19: "no free connection slot for dport=52316" on attempt 6).  FIN/RST are handled
 *     below; the headroom keeps a retry from racing a half-closed connection. --- */
#define MAX_CONN 8
enum { CONN_FREE = 0, CONN_LOCKDOWN = 1, CONN_SERVICE = 2, CONN_DIAG = 3 };

typedef struct {
    int used;
    int kind;
    uint16_t host_port;
    uint16_t svc_port;
    uint32_t our_seq;
    uint32_t ack;
    int sock;                                  /* CONN_SERVICE: fd to the Java host, else -1 */
    volatile int stop;                         /* CONN_SERVICE: ask the pump thread to leave */
    volatile int dead;                         /* CONN_SERVICE: pump finished, slot may be reused */
    int pump_started;
    uint8_t pending[LOCKDOWN_MAX_PLIST];       /* CONN_LOCKDOWN: decrypted bytes, not yet a whole plist */
    size_t pending_len;
    /* Per-connection TLS.  the emulator keeps SEVERAL lockdown streams open at once (pairing/control plus
     * the syslog_relay diagnostics), each running its own StartSession + TLS.  One global SSL object
     * meant the second stream tore down the first ("TLS channel failed (SSLException)" on the host)
     * and every attempt died in phase=CONTROL, so the session belongs to the connection. */
    SSL *ssl;
    BIO *bio_net;
    int tls_started;
    int tls_up;
} conn_t;

static conn_t g_conns[MAX_CONN];

/* --- state --- */
static int g_ep_out = -1;              /* ep1: OUT endpoint, host -> device (we read) */
static int g_ep_in = -1;               /* ep2: IN endpoint, device -> host (we write) */
static pthread_mutex_t g_tx_lock = PTHREAD_MUTEX_INITIALIZER;
static uint16_t g_seq = 0;             /* our mux sequence */
static uint16_t g_ack = 0;             /* mux acknowledgement we owe the host */
static int g_reader_started = 0;

static SSL_CTX *g_ctx = NULL;      /* shared certificate/context; the sessions live in conn_t */

/* forward declarations (order matters: the reader uses these) */
static void mux_send_word8(uint32_t proto, const uint8_t *payload, size_t len, uint32_t word8);
static void tcp_send(conn_t *c, const uint8_t *payload, size_t plen, uint8_t flags);
static void mux_stop(void);
static void tls_feed(conn_t *c, const uint8_t *data, size_t len);
static void tls_drain(conn_t *c);

/* --- byte order: classic usbmux is network order.  The captured version frame was
 *     00 00 00 00 | 00 00 00 14 | 00 00 00 02 | ...  (proto=0, length=20, version=2) --- */
static uint32_t rd32(const uint8_t *p) {
    return ((uint32_t)p[0] << 24) | ((uint32_t)p[1] << 16) | ((uint32_t)p[2] << 8) | (uint32_t)p[3];
}
static void wr32(uint8_t *p, uint32_t v) {
    p[0] = (v >> 24) & 0xff; p[1] = (v >> 16) & 0xff; p[2] = (v >> 8) & 0xff; p[3] = v & 0xff;
}
static void wr16(uint8_t *p, uint16_t v) { p[0] = (v >> 8) & 0xff; p[1] = v & 0xff; }

/* Frames are handed to a writer thread instead of being written inline.  Reason (measured on lmi):
 * an epfile write to a full IN endpoint blocks in the kernel REGARDLESS of O_NONBLOCK, so a write
 * issued from the reader thread parks that thread -- it then stops draining the OUT endpoint, the
 * host's next bulk write times out with "USBMUX write transferred -1 of N bytes", its reader thread
 * dies, and our write never returns.  The reader must only ever enqueue. */
#define TX_QUEUE_SLOTS 64
typedef struct {
    size_t len;
    uint32_t proto;
    uint8_t data[MUX_HEADER_BYTES + TCP_MAX_PAYLOAD];
} tx_slot_t;
static tx_slot_t g_txq[TX_QUEUE_SLOTS];
static int g_txq_head = 0, g_txq_tail = 0, g_txq_count = 0;
static pthread_mutex_t g_txq_lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t g_txq_cond = PTHREAD_COND_INITIALIZER;
static pthread_t g_txq_thread;
static int g_txq_run = 0, g_txq_started = 0;
static unsigned long g_txq_drops = 0;

static void *txq_writer(void *arg) {
    (void)arg;
    for (;;) {
        tx_slot_t slot;
        pthread_mutex_lock(&g_txq_lock);
        while (g_txq_count == 0 && g_txq_run) pthread_cond_wait(&g_txq_cond, &g_txq_lock);
        if (!g_txq_run && g_txq_count == 0) { pthread_mutex_unlock(&g_txq_lock); break; }
        slot = g_txq[g_txq_head];
        g_txq_head = (g_txq_head + 1) % TX_QUEUE_SLOTS;
        g_txq_count--;
        pthread_cond_broadcast(&g_txq_cond);
        pthread_mutex_unlock(&g_txq_lock);

        if (g_ep_in < 0) continue;
        ssize_t n = -1;
        int last_errno = 0;
        for (int attempt = 0; attempt < 250 && n < 0; attempt++) {
            n = write(g_ep_in, slot.data, slot.len);
            if (n < 0) { last_errno = errno; usleep(4000); }
        }
        if (n < 0) {
            fprintf(stderr, "mux: TX proto=%u len=%zu DROPPED errno=%d\n", slot.proto, slot.len, last_errno);
        } else if (slot.len > MUX_HEADER_BYTES + 20) {
            fprintf(stderr, "mux: TX proto=%u len=%zu write=%zd\n", slot.proto, slot.len, n);
        }
    }
    return NULL;
}

/* Write one mux frame.  Caller must hold g_tx_lock (tcp_send updates its connection's sequence
 * number and the frame in one critical section, so the lock cannot live only in here). */
static void mux_write_frame(uint32_t proto, const uint8_t *payload, size_t len, uint32_t word8) {
    uint8_t frame[MUX_HEADER_BYTES + TCP_MAX_PAYLOAD];
    if (len > TCP_MAX_PAYLOAD) len = TCP_MAX_PAYLOAD;
    if (g_ep_in < 0) return;
    wr32(frame, proto);
    wr32(frame + 4, (uint32_t)(MUX_HEADER_BYTES + len));
    wr32(frame + 8, word8);                 /* device->host frames carry no 0xFEEDFACE magic */
    wr16(frame + 12, g_seq);
    wr16(frame + 14, g_ack);
    if (payload && len) memcpy(frame + MUX_HEADER_BYTES, payload, len);
    pthread_mutex_lock(&g_txq_lock);
    while (g_txq_count == TX_QUEUE_SLOTS) {
        if (len == 0) {                       /* a bare control ACK is the cheapest thing to lose */
            g_txq_drops++;
            pthread_mutex_unlock(&g_txq_lock);
            g_seq++;
            return;
        }
        /* A frame carrying payload must not be lost: give the writer a moment to drain. */
        struct timespec ts;
        clock_gettime(CLOCK_REALTIME, &ts);
        ts.tv_nsec += 500000000L;
        if (ts.tv_nsec >= 1000000000L) { ts.tv_sec += 1; ts.tv_nsec -= 1000000000L; }
        if (pthread_cond_timedwait(&g_txq_cond, &g_txq_lock, &ts) != 0 && g_txq_count == TX_QUEUE_SLOTS) {
            g_txq_drops++;
            fprintf(stderr, "mux: TX queue full, dropping proto=%u len=%zu payload\n", proto, len);
            pthread_mutex_unlock(&g_txq_lock);
            g_seq++;
            return;
        }
    }
    {
        tx_slot_t *slot = &g_txq[g_txq_tail];
        slot->len = MUX_HEADER_BYTES + len;
        slot->proto = proto;
        memcpy(slot->data, frame, MUX_HEADER_BYTES + len);
        g_txq_tail = (g_txq_tail + 1) % TX_QUEUE_SLOTS;
        g_txq_count++;
        pthread_cond_signal(&g_txq_cond);
    }
    pthread_mutex_unlock(&g_txq_lock);
    g_seq++;
}

static void mux_send_word8(uint32_t proto, const uint8_t *payload, size_t len, uint32_t word8) {
    pthread_mutex_lock(&g_tx_lock);
    mux_write_frame(proto, payload, len, word8);
    pthread_mutex_unlock(&g_tx_lock);
}

/* TCP header over usbmux (20 B, network order): sport, dport, seq, ack, data offset, flags,
 * window.  Mirrors Iap2UsbMuxHost.sendTcp(). */
static void tcp_send(conn_t *c, const uint8_t *payload, size_t plen, uint8_t flags) {
    uint8_t tcp[20 + TCP_MAX_PAYLOAD];
    if (!c || !c->used) return;
    if (plen > TCP_MAX_PAYLOAD) plen = TCP_MAX_PAYLOAD;
    memset(tcp, 0, 20 + plen);
    wr16(tcp + 0, c->svc_port);
    wr16(tcp + 2, c->host_port);
    wr32(tcp + 4, c->our_seq);
    wr32(tcp + 8, c->ack);
    tcp[12] = 0x50;
    tcp[13] = flags;
    wr16(tcp + 14, 0x0200);
    if (payload && plen) memcpy(tcp + 20, payload, plen);
    pthread_mutex_lock(&g_tx_lock);
    c->our_seq += (uint32_t)plen;
    mux_write_frame(PROTO_TCP, tcp, 20 + plen, 0);
    pthread_mutex_unlock(&g_tx_lock);
}

/* --- connection table ------------------------------------------------------------------------- */

/* Every lockdown connection carries its own TLS session, and a fresh one always starts in plaintext
 * (StartSession).  Dropping it on release/re-accept keeps a half-open session from swallowing the
 * next connection's plaintext plist ("tls: read r=-1 err=1"). */
static void tls_reset(conn_t *c) {
    if (!c) return;
    c->tls_started = 0;
    c->tls_up = 0;
    if (c->ssl) { SSL_free(c->ssl); c->ssl = NULL; }
    if (c->bio_net) { BIO_free(c->bio_net); c->bio_net = NULL; }
}

static void conn_release(conn_t *c) {
    if (!c || !c->used) return;
    if (c->kind == CONN_LOCKDOWN) tls_reset(c);
    if (c->sock >= 0) {
        c->stop = 1;
        shutdown(c->sock, SHUT_RDWR);
        close(c->sock);
        c->sock = -1;
    }
    memset(c, 0, sizeof *c);
    c->sock = -1;
}

/* The host identifies a mux connection by ITS source port, and it opens several connections to the
 * same service port (the emulator dials 62078 again to open com.apple.carkit.service right after the
 * syslog relay).  Matching inbound data by our own port therefore handed every later connection's
 * bytes to the first one -- its StartSession was never seen and the carkit request never arrived.
 *
 * the emulator also REUSES source ports across bring-up attempts (attempt N dials sport 1,2,3 again), so a
 * slot left over from a previous attempt matches the new SYN on host port alone while carrying the
 * old service port and kind.  A SYN therefore has to match on both ports; anything else is stale. */
static conn_t *conn_for_host_port(uint16_t host_port) {
    for (int i = 0; i < MAX_CONN; i++) {
        if (g_conns[i].used && g_conns[i].host_port == host_port) return &g_conns[i];
    }
    return NULL;
}

static conn_t *conn_for_flow(uint16_t host_port, uint16_t svc_port) {
    for (int i = 0; i < MAX_CONN; i++) {
        if (g_conns[i].used && g_conns[i].host_port == host_port && g_conns[i].svc_port == svc_port)
            return &g_conns[i];
    }
    return NULL;
}

/* Drop every slot that still holds host_port with a different service port: those belong to earlier
 * bring-up attempts whose FIN we never saw (the host does not send one when it aborts a run). */
static void conn_release_stale(uint16_t host_port, uint16_t svc_port) {
    for (int i = 0; i < MAX_CONN; i++) {
        if (g_conns[i].used && g_conns[i].host_port == host_port && g_conns[i].svc_port != svc_port) {
            fprintf(stderr, "mux: reusing host port %u for %u, dropping stale %s slot\n",
                    host_port, svc_port,
                    g_conns[i].kind == CONN_LOCKDOWN ? "lockdown"
                    : g_conns[i].kind == CONN_DIAG ? "diagnostics" : "service");
            conn_release(&g_conns[i]);
        }
    }
}

static conn_t *conn_for_port(uint16_t svc_port) {
    for (int i = 0; i < MAX_CONN; i++) {
        if (g_conns[i].used && g_conns[i].svc_port == svc_port) {
            if (g_conns[i].dead && g_conns[i].kind == CONN_SERVICE) {
                conn_release(&g_conns[i]);      /* the reader owns freeing; the pump only sets dead */
                return NULL;
            }
            return &g_conns[i];
        }
    }
    return NULL;
}

static conn_t *conn_alloc(uint16_t host_port, uint16_t svc_port, int kind) {
    conn_t *c = NULL;
    for (int i = 0; i < MAX_CONN; i++) {
        if (!g_conns[i].used) { c = &g_conns[i]; break; }
        if (g_conns[i].dead && g_conns[i].kind == CONN_SERVICE) { conn_release(&g_conns[i]); c = &g_conns[i]; break; }
    }
    if (!c) return NULL;
    memset(c, 0, sizeof *c);
    c->used = 1;
    c->kind = kind;
    c->host_port = host_port;
    c->svc_port = svc_port;
    c->sock = -1;
    return c;
}

/* --- lockdown --------------------------------------------------------------------------------- */

/* Answer one complete request plist.  StartSession is the only plaintext request; StartService
 * arrives inside TLS and hands the host the port of the carkit byte pipe. */
/* Replies must go back the way the request came in: SetValue/GetValue/Pair arrive in plaintext,
 * StartService arrives INSIDE TLS, and answering it with a raw tcp_send put cleartext on a TLS
 * stream -- the host never parsed the Port and every attempt failed at LockdownCarKitClient.open. */
static void lockdown_send(conn_t *c, const char *out, size_t n) {
    if (c->tls_started && c->ssl) {
        int w = SSL_write(c->ssl, out, (int)n);
        if (w <= 0) fprintf(stderr, "tls: write r=%d err=%d\n", w, SSL_get_error(c->ssl, w));
        tls_drain(c);
    } else {
        tcp_send(c, (const uint8_t *)out, n, 0x18);
    }
}

static void lockdown_dispatch(conn_t *c, const char *xml, size_t len) {
    char out[LOCKDOWN_MAX_PLIST];
    lockdown_request req;
    size_t n;

    if (!lockdown_parse(xml, len, &req)) {
        fprintf(stderr, "lockdown: request without <key>Request</key> (%zu B) - ignored\n", len);
        return;
    }
    /* the emulator's first plaintext request is a SetValue and it waits for the reply: with the old
     * "ignore GetValue/SetValue" behaviour it timed out and re-dialled the lockdown port in a loop
     * (three identical retries in one session).  We acknowledge it -- we have nothing to persist --
     * and log Domain/Key plus the raw bytes, because the Key names are exactly what the next
     * iteration has to answer. */
    if (strcmp(req.request, LOCKDOWN_REQ_SET_VALUE) == 0) {
        n = lockdown_reply_ack(out, sizeof out, LOCKDOWN_REQ_SET_VALUE);
        fprintf(stderr, "lockdown: SetValue domain=%s key=%s has_value=%d -> ack (%zu B) raw=%.240s\n",
                req.domain[0] ? req.domain : "-", req.key[0] ? req.key : "-", req.has_value, n, xml);
        lockdown_send(c, out, n);
        return;
    }
    if (strcmp(req.request, LOCKDOWN_REQ_GET_VALUE) == 0) {
        /* Values we can stand behind.  Everything else is answered with a well-formed empty Value
         * and logged loudly, so the host never stalls and the log says what still has to be filled
         * in (identity keys like UniqueDeviceID are deliberately NOT invented: the pair record path
         * keys off them). */
        const char *val = "";
        if (strcmp(req.key, "ProductType") == 0) val = "iPhone10,1";
        else if (strcmp(req.key, "DeviceName") == 0) val = "iPhone";
        /* Locally administered placeholder; the value the host paired with is what a real
         * unit answers here.  Replace it if your receiver reads this key. */
        else if (strcmp(req.key, "WiFiAddress") == 0) val = "02:00:00:00:00:01";
        else if (strcmp(req.key, "DevicePublicKey") == 0) {
            /* The host builds its pair record from this and rejects anything that is not <data>
             * ("DevicePublicKey was not data", LockdownPairingClient.kt:98).  The bytes are the
             * device's PKCS#1 RSA public key in PEM form; the matching private key is only used by
             * the TLS server above, because this device never has to prove ownership -- the peer
             * checks the host's side of the pairing, not ours.
             * The value is resolved here instead of being compiled in, so the advertised key always
             * matches the certificate the same binary presents. */
            static char pubkey_b64[2048];
            const char *value = DEVICE_PUBKEY_PKCS1_PEM_B64;
            if (value[0] == 0 && device_pubkey_from_cert(pubkey_b64, sizeof pubkey_b64))
                value = pubkey_b64;
            if (value[0] == 0)
                fprintf(stderr, "lockdown: WARNING: no DevicePublicKey available - install the lockdown pair first (%s; docs/en/building.md)\n", LOCKDOWN_CERT);
            n = lockdown_reply_value_data(out, sizeof out, req.key, value);
            fprintf(stderr, "lockdown: GetValue %s -> <data> %zu B%s\n", req.key, n, value[0] ? "" : " (empty)");
            tcp_send(c, (const uint8_t *)out, n, 0x18);
            return;
        }
        n = lockdown_reply_value_string(out, sizeof out, req.key, val);
        fprintf(stderr, "lockdown: GetValue domain=%s key=%s -> '%s'%s (%zu B)\n",
                req.domain[0] ? req.domain : "-", req.key[0] ? req.key : "-", val,
                val[0] ? "" : "  <-- NO VALUE YET", n);
        lockdown_send(c, out, n);
        return;
    }
    if (strcmp(req.request, "Pair") == 0 || strcmp(req.request, "ValidatePair") == 0) {
        /* A fake device accepts the pairing: it verifies nothing and stores nothing.  The host is
         * what needs the pair record (it keeps it in its own app data), so answering success is the
         * whole contract here.  Keep the reply shape minimal -- the emulator only looks for Error/EscrowBag. */
        n = lockdown_reply_ack(out, sizeof out, req.request);
        fprintf(stderr, "lockdown: %s accepted -> ack (%zu B)\n", req.request, n);
        lockdown_send(c, out, n);
        return;
    }
    if (strcmp(req.request, LOCKDOWN_REQ_START_SESSION) == 0) {
        n = lockdown_reply_start_session(out, sizeof out, "OKCARLOCKDOWN0001");
        fprintf(stderr, "lockdown: StartSession -> EnableSessionSSL=true (%zu B)\n", n);
        lockdown_send(c, out, n);               /* the reply itself is still plaintext ... */
        c->tls_started = 1;                     /* ... everything after it is TLS */
        return;
    }
    if (strcmp(req.request, LOCKDOWN_REQ_START_SERVICE) == 0) {
        if (strcmp(req.service, LOCKDOWN_SERVICE_CARKIT) == 0) {
            n = lockdown_reply_start_service(out, sizeof out, req.service, LOCKDOWN_CARKIT_PORT);
            fprintf(stderr, "lockdown: StartService %s -> Port %d (no EnableServiceSSL)\n",
                    req.service, LOCKDOWN_CARKIT_PORT);
        } else {
            n = lockdown_reply_start_service(out, sizeof out, req.service, LOCKDOWN_DIAG_PORT);
            fprintf(stderr, "lockdown: StartService %s -> Port %d (diagnostic, closed after one line)\n",
                    req.service[0] ? req.service : "(none)", LOCKDOWN_DIAG_PORT);
        }
        lockdown_send(c, out, n);
        return;
    }
    /* GetValue/SetValue and friends: ignored today, exactly as before.  Answering an Error here
     * would turn "silently unsupported" into a hard failure for requests we may want to add. */
    fprintf(stderr, "lockdown: ignoring request %s\n", req.request);
}

/* Decrypted lockdown bytes: reassemble the four-byte length + plist framing across TLS reads. */
static void lockdown_plaintext(conn_t *c, const uint8_t *data, size_t len) {
    while (len > 0) {
        size_t take;
        if (c->pending_len < 4) {
            take = 4 - c->pending_len;
            if (take > len) take = len;
            memcpy(c->pending + c->pending_len, data, take);
            c->pending_len += take;
            data += take;
            len -= take;
            if (c->pending_len < 4) return;
        }
        {
            uint32_t framed = ((uint32_t)c->pending[0] << 24) | ((uint32_t)c->pending[1] << 16) |
                              ((uint32_t)c->pending[2] << 8) | (uint32_t)c->pending[3];
            size_t want;
            if (framed == 0 || framed > LOCKDOWN_MAX_PLIST - 4) {
                fprintf(stderr, "lockdown: bad frame length %u - dropping the buffer\n", framed);
                c->pending_len = 0;
                return;
            }
            want = 4 + framed;
            if (c->pending_len < want) {
                take = want - c->pending_len;
                if (take > len) take = len;
                memcpy(c->pending + c->pending_len, data, take);
                c->pending_len += take;
                data += take;
                len -= take;
                if (c->pending_len < want) return;
            }
            c->pending[want] = '\0';
            lockdown_dispatch(c, (const char *)(c->pending + 4), framed);
            c->pending_len = 0;
        }
    }
}

/* --- the carkit byte pipe: mux <-> local abstract socket -------------------------------------- */

static int service_connect(conn_t *c) {
    struct sockaddr_un sun;
    socklen_t addrlen;
    struct timeval tv;
    int fd, tries = 8;

    fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (fd < 0) { fprintf(stderr, "carkit: socket() failed errno=%d\n", errno); return -1; }
    memset(&sun, 0, sizeof sun);
    sun.sun_family = AF_UNIX;
    sun.sun_path[0] = '\0';                    /* abstract namespace, same as LocalServerSocket */
    snprintf(sun.sun_path + 1, sizeof sun.sun_path - 1, "%s", CARKIT_SOCKET_NAME);
    addrlen = (socklen_t)(offsetof(struct sockaddr_un, sun_path) + 1 + strlen(CARKIT_SOCKET_NAME));
    /* Bounded write and a read timeout: the pump thread needs to notice stop/teardown, and one slow
     * reader must not stall the mux reader thread that also writes into this socket. */
    tv.tv_sec = 0; tv.tv_usec = 500000;
    setsockopt(fd, SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof tv);
    setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof tv);
    while (connect(fd, (struct sockaddr *)&sun, addrlen) != 0) {
        if (--tries <= 0) {
            fprintf(stderr, "carkit: no listener on abstract socket '%s' (errno=%d)\n",
                    CARKIT_SOCKET_NAME, errno);
            close(fd);
            return -1;
        }
        usleep(100000);
    }
    c->sock = fd;
    fprintf(stderr, "carkit: bridged mux port %u to abstract socket '%s'\n", c->svc_port, CARKIT_SOCKET_NAME);
    return 0;
}

static void *service_pump(void *arg) {
    conn_t *c = arg;
    uint8_t buf[TCP_MAX_PAYLOAD];
    for (;;) {
        ssize_t n;
        int fd;
        if (c->stop) break;
        fd = c->sock;
        if (fd < 0) break;
        n = read(fd, buf, sizeof buf);
        if (n > 0) { tcp_send(c, buf, (size_t)n, 0x18); continue; }
        if (n < 0 && (errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR)) continue;
        fprintf(stderr, "carkit: peer closed (n=%zd errno=%d)\n", n, errno);
        break;
    }
    if (c->used) tcp_send(c, NULL, 0, 0x14);   /* RST|ACK: the host should not wait for a timeout */
    c->dead = 1;
    return NULL;
}

static void service_start(conn_t *c) {
    pthread_t t;
    if (c->pump_started) return;
    if (pthread_create(&t, NULL, service_pump, c) != 0) {
        fprintf(stderr, "carkit: pump thread failed\n");
        return;
    }
    pthread_detach(t);
    c->pump_started = 1;
}

/* Host bytes on the service port go straight to the Java side. */
static void service_to_peer(conn_t *c, const uint8_t *data, size_t len) {
    size_t off = 0;
    if (c->sock < 0) return;
    while (off < len) {
        ssize_t n = write(c->sock, data + off, len - off);
        if (n > 0) { off += (size_t)n; continue; }
        if (n < 0 && errno == EINTR) continue;
        fprintf(stderr, "carkit: peer write failed (errno=%d), dropping %zu B\n", errno, len - off);
        c->dead = 1;
        return;
    }
}

/* --- TLS server over the mux byte pipe (BoringSSL BIO pair; there is no socket here) --- */
static void tls_drain(conn_t *c) {
    uint8_t out[TCP_MAX_PAYLOAD];
    int n;
    while (c->bio_net && (n = BIO_read(c->bio_net, out, (int)sizeof out)) > 0) {
        fprintf(stderr, "tls: TX %d bytes\n", n);
        tcp_send(c, out, (size_t)n, 0x18);
    }
}

static void tls_begin(conn_t *c) {
    BIO *ssl_bio = NULL;
    if (!g_ctx) {
        g_ctx = SSL_CTX_new(TLS_server_method());
        if (!g_ctx) { fprintf(stderr, "tls: SSL_CTX_new failed\n"); return; }
        if (!SSL_CTX_use_certificate_chain_file(g_ctx, LOCKDOWN_CERT))
            fprintf(stderr, "tls: cert load failed\n");
        if (!SSL_CTX_use_PrivateKey_file(g_ctx, LOCKDOWN_KEY, SSL_FILETYPE_PEM))
            fprintf(stderr, "tls: key load failed\n");
        if (!SSL_CTX_check_private_key(g_ctx))
            fprintf(stderr, "tls: cert/key do not match\n");
        SSL_CTX_set_verify(g_ctx, SSL_VERIFY_NONE, NULL);   /* we never validate the host */
    }
    if (!BIO_new_bio_pair(&c->bio_net, 0, &ssl_bio, 0)) {
        fprintf(stderr, "tls: bio pair failed\n");
        return;
    }
    c->ssl = SSL_new(g_ctx);
    SSL_set_bio(c->ssl, ssl_bio, ssl_bio);
    SSL_set_accept_state(c->ssl);
    c->tls_up = 0;
    fprintf(stderr, "tls: server ready\n");
}

static void tls_feed(conn_t *c, const uint8_t *data, size_t len) {
    if (!c->ssl) { tls_begin(c); if (!c->ssl) return; }
    if (BIO_write(c->bio_net, data, (int)len) <= 0) fprintf(stderr, "tls: BIO_write failed\n");
    if (!c->tls_up) {
        int r = SSL_do_handshake(c->ssl);
        tls_drain(c);
        if (r == 1) {
            c->tls_up = 1;
            fprintf(stderr, "tls: handshake complete\n");
        } else {
            int e = SSL_get_error(c->ssl, r);
            unsigned long qe = ERR_get_error();
            char ebuf[256];
            ebuf[0] = '\0';
            if (qe) ERR_error_string_n(qe, ebuf, sizeof ebuf);
            fprintf(stderr, "tls: handshake r=%d err=%d errq=%lu '%s'\n", r, e, qe, ebuf);
            if (e != SSL_ERROR_WANT_READ) return;
        }
    }
    if (!c->tls_up) return;
    for (;;) {
        uint8_t in[TCP_MAX_PAYLOAD];
        int r = SSL_read(c->ssl, in, (int)sizeof in);
        if (r <= 0) {
            int e = SSL_get_error(c->ssl, r);
            if (e != SSL_ERROR_WANT_READ) fprintf(stderr, "tls: read r=%d err=%d\n", r, e);
            break;
        }
        fprintf(stderr, "tls: RX %d bytes: %.120s\n", r, (const char *)in);
        lockdown_plaintext(c, in, (size_t)r);
    }
    tls_drain(c);
}

static void *mux_reader(void *arg) {
    (void)arg;
    uint8_t buf[MUX_MAX_FRAME * 2];
    size_t have = 0;
    for (;;) {
        if (g_ep_out < 0) { usleep(200000); continue; }
        ssize_t n = read(g_ep_out, buf + have, sizeof buf - have);
        if (n < 0) {
            if (errno == EINTR) continue;
            /* A transient read error used to drop the endpoint for good: the interface then stayed
             * enabled, nobody drained the OUT endpoint and every host write timed out
             * ("USBMUX write transferred -1 of N bytes").  Reopen instead of giving up. */
            fprintf(stderr, "mux: ep1 read errno=%d - reopening endpoint\n", errno);
            pthread_mutex_lock(&g_tx_lock);
            if (g_ep_out >= 0) close(g_ep_out);
            if (g_ep_in >= 0) close(g_ep_in);
            g_ep_out = open(FFS_EP_OUT, O_RDWR);
            g_ep_in = open(FFS_EP_IN, O_RDWR);
            pthread_mutex_unlock(&g_tx_lock);
            fprintf(stderr, "mux: reopened ep1=%d ep2=%d\n", g_ep_out, g_ep_in);
            usleep(200000);
            continue;
        }
        have += (size_t)n;
        for (;;) {
            if (have < MUX_HEADER_BYTES) break;
            uint32_t proto = rd32(buf);
            uint32_t len = rd32(buf + 4);
            if (len < MUX_HEADER_BYTES || len > MUX_MAX_FRAME) {
                fprintf(stderr, "mux: bad length %u (proto=%u) - resyncing\n", len, proto);
                have = 0;
                break;
            }
            if (have < len) break;
            g_ack = (uint16_t)(((((uint32_t)buf[12] << 8) | buf[13]) + 1) & 0xffff);
            fprintf(stderr, "mux: RX proto=%u len=%u seq=%u ack=%u payload=%u\n",
                    proto, len, (unsigned)((buf[12] << 8) | buf[13]),
                    (unsigned)((buf[14] << 8) | buf[15]), len - MUX_HEADER_BYTES);
            /* Head of every TCP payload in escaped text: lockdown traffic is XML, so this shows the
             * request/key a host sends (or that it sent nothing at all) without a packet capture.
             * Measured need: the emulator answered our SetValue ack and then never delivered the GetValue
             * its own source says comes next -- this line is what distinguishes "never sent" from
             * "sent but mis-framed". */
            if (proto == PROTO_TCP && len > MUX_HEADER_BYTES + TCP_HEADER_BYTES) {
                const uint8_t *d = buf + MUX_HEADER_BYTES + TCP_HEADER_BYTES;
                size_t dn = len - MUX_HEADER_BYTES - TCP_HEADER_BYTES, i, lim = dn > 96 ? 96 : dn;
                fprintf(stderr, "mux: RX head(%zu/%zu)=", lim, dn);
                for (i = 0; i < lim; i++) {
                    unsigned ch = d[i];
                    fputc(ch >= 0x20 && ch < 0x7f ? (int)ch : '.', stderr);
                }
                fputc('\n', stderr);
            }
            if (proto == PROTO_VERSION && len >= VERSION_MESSAGE_BYTES) {
                uint8_t pad[4] = { 0, 0, 0, 0 };
                /* the emulator checks protocol==0, length==20 and word8==USBMUX_VERSION(2). */
                mux_send_word8(PROTO_VERSION, pad, sizeof pad, USBMUX_VERSION);
            } else if (proto == PROTO_TCP) {
                const uint8_t *tcp = buf + MUX_HEADER_BYTES;
                uint32_t sport = ((uint32_t)tcp[0] << 8) | tcp[1];
                uint32_t dport = ((uint32_t)tcp[2] << 8) | tcp[3];
                uint32_t seq = rd32(tcp + 4);
                uint8_t flags = tcp[13];
                size_t plen = (len - MUX_HEADER_BYTES) > 20 ? (size_t)(len - MUX_HEADER_BYTES) - 20 : 0;
                const uint8_t *payload = tcp + 20;
                if ((flags & 0x02) && !(flags & 0x10) &&
                    ((uint16_t)dport == LOCKDOWN_PORT || (uint16_t)dport == LOCKDOWN_CARKIT_PORT ||
                     (uint16_t)dport == LOCKDOWN_DIAG_PORT)) {
                    /* SYN on a port we serve: answer Result + SYN|ACK */
                    uint8_t reply[20];
                    uint8_t ok[4] = { 0, 0, 0, 0 };
                    int kind = ((uint16_t)dport == LOCKDOWN_PORT) ? CONN_LOCKDOWN
                             : ((uint16_t)dport == LOCKDOWN_DIAG_PORT) ? CONN_DIAG : CONN_SERVICE;
                    conn_release_stale((uint16_t)sport, (uint16_t)dport);
                    conn_t *c = conn_for_flow((uint16_t)sport, (uint16_t)dport);
                    if (!c) c = conn_alloc((uint16_t)sport, (uint16_t)dport, kind);
                    if (!c) {
                        uint8_t rst[20];
                        fprintf(stderr, "mux: no free connection slot for dport=%u\n", dport);
                        memset(rst, 0, sizeof rst);
                        wr16(rst + 0, (uint16_t)dport);
                        wr16(rst + 2, (uint16_t)sport);
                        rst[12] = 0x50;
                        rst[13] = 0x14;                            /* RST|ACK */
                        mux_send_word8(PROTO_TCP, rst, sizeof rst, 0);
                    } else {
                        c->our_seq = 0;
                        c->ack = seq + 1;
                        /* A service connection is useless without the Java peer: connect first so a
                         * missing listener fails now, with one clear log line, instead of stalling
                         * the host on a pipe nobody reads. */
                        if (c->kind == CONN_SERVICE && service_connect(c) != 0) {
                            memset(reply, 0, sizeof reply);
                            wr16(reply + 0, (uint16_t)dport);
                            wr16(reply + 2, (uint16_t)sport);
                            reply[12] = 0x50;
                            reply[13] = 0x14;                      /* RST|ACK */
                            mux_send_word8(PROTO_RESULT, ok, sizeof ok, 0);
                            mux_send_word8(PROTO_TCP, reply, sizeof reply, 0);
                            conn_release(c);
                        } else {
                            memset(reply, 0, sizeof reply);
                            wr16(reply + 0, (uint16_t)dport);
                            wr16(reply + 2, (uint16_t)sport);
                            wr32(reply + 8, seq + 1);
                            reply[12] = 0x50;
                            reply[13] = 0x12;
                            wr16(reply + 14, 0x0200);
                            fprintf(stderr, "mux: accept tcp %u -> %u (%s)\n", sport, dport,
                                    c->kind == CONN_LOCKDOWN ? "lockdown"
                                    : c->kind == CONN_DIAG ? "diagnostics" : "carkit service");
                            if (c->kind == CONN_LOCKDOWN) tls_reset(c);
                            mux_send_word8(PROTO_RESULT, ok, sizeof ok, 0);
                            c->our_seq = 1;                        /* SYN consumed one sequence */
                            mux_send_word8(PROTO_TCP, reply, sizeof reply, 0);
                            if (c->kind == CONN_SERVICE) service_start(c);
                            if (c->kind == CONN_DIAG) {
                                /* The host reads this in its own daemon thread, so the channel must
                                 * stay open: closing it right after one line made the emulator sit on its
                                 * "preparing CarPlay" screen instead of opening the carkit service. */
                                const char *hello = "okcar: diagnostics\n";
                                tcp_send(c, (const uint8_t *)hello, strlen(hello), 0x18);
                            }
                        }
                    }
                } else if ((flags & 0x02) && !(flags & 0x10)) {
                    /* Only the lockdown port and the service port we handed out are ours;
                     * refusing explicitly beats bridging a stray port to the carkit socket. */
                    uint8_t rst[20];
                    fprintf(stderr, "mux: refusing connection to unserved port %u\n", dport);
                    memset(rst, 0, sizeof rst);
                    wr16(rst + 0, (uint16_t)dport);
                    wr16(rst + 2, (uint16_t)sport);
                    rst[12] = 0x50;
                    rst[13] = 0x14;                            /* RST|ACK */
                    mux_send_word8(PROTO_TCP, rst, sizeof rst, 0);
                } else if (flags & (0x01 | 0x04)) {
                    /* FIN or RST: the flow is over, free the slot.  Without this the host's retry
                     * (new bring-up, same source ports) found every slot still marked used and the
                     * carkit port was refused -- "no free connection slot for dport=52316". */
                    conn_t *c = conn_for_flow((uint16_t)sport, (uint16_t)dport);
                    if (c) {
                        fprintf(stderr, "mux: closing tcp %u -> %u (%s)\n", sport, dport,
                                c->kind == CONN_LOCKDOWN ? "lockdown"
                                : c->kind == CONN_DIAG ? "diagnostics" : "carkit service");
                        conn_release(c);
                    }
                } else if (plen) {
                    conn_t *c = conn_for_host_port((uint16_t)sport);
                    if (!c) {
                        fprintf(stderr, "mux: %zu B for unknown port %u - dropped\n", plen, dport);
                    } else {
                        c->ack = seq + (uint32_t)plen;
                        if (c->kind == CONN_DIAG) { /* nothing to read: the stream is already closed */ }
                        else if (c->kind == CONN_SERVICE) service_to_peer(c, payload, plen);
                        else if (!c->tls_started) lockdown_plaintext(c, payload, plen);
                        else tls_feed(c, payload, plen);
                        /* No per-frame ACK: a blocking FFS epfile write parks the reader thread
                         * (O_NONBLOCK does not make it return EAGAIN), so ACKing here stopped us
                         * draining OUT and the peer's own ACK write timed out
                         * ("USBMUX write transferred -1 of 36 bytes") -> it tore the stack down
                         * before ever sending GetValue.  The peer works off received payloads and
                         * does not need our ACK (measured 2026-10-06, lmi). */
                    }
                }
            }
            memmove(buf, buf + len, have - len);
            have -= len;
        }
    }
    return NULL;
}

/* the emulator re-runs setConfiguration/setInterface on every bring-up attempt, so the FFS endpoints are
 * torn down and recreated underneath us.  A stale pair of fds stops our side draining the OUT
 * endpoint, after which the host's writes fail with "USBMUX write transferred -1 of N bytes".
 * Hence: reopen on every ENABLE, close and reset per-session state on DISABLE. */
static void mux_stop(void) {
    if (g_txq_started) {
        pthread_mutex_lock(&g_txq_lock);
        g_txq_run = 0;
        pthread_cond_broadcast(&g_txq_cond);
        pthread_mutex_unlock(&g_txq_lock);
        pthread_join(g_txq_thread, NULL);
        g_txq_started = 0;
        g_txq_head = g_txq_tail = g_txq_count = 0;
    }
    pthread_mutex_lock(&g_tx_lock);
    for (int i = 0; i < MAX_CONN; i++) conn_release(&g_conns[i]);
    if (g_ep_out >= 0) close(g_ep_out);
    if (g_ep_in >= 0) close(g_ep_in);
    g_ep_out = -1;
    g_ep_in = -1;
    if (g_ctx) { SSL_CTX_free(g_ctx); g_ctx = NULL; }
    pthread_mutex_unlock(&g_tx_lock);
}

static void mux_start(void) {
    mux_stop();
    pthread_mutex_lock(&g_txq_lock);
    g_txq_run = 1;
    g_txq_head = g_txq_tail = g_txq_count = 0;
    pthread_mutex_unlock(&g_txq_lock);
    if (pthread_create(&g_txq_thread, NULL, txq_writer, NULL) == 0) g_txq_started = 1;
    g_ep_out = open(FFS_EP_OUT, O_RDWR);
    /* IN is opened non-blocking on purpose: see the deadlock note in mux_write_frame(). */
    g_ep_in = open(FFS_EP_IN, O_RDWR | O_NONBLOCK);
    fprintf(stderr, "mux: endpoints reset ep1=%d ep2=%d\n", g_ep_out, g_ep_in);
    if (g_ep_out >= 0 && g_ep_in >= 0 && !g_reader_started) {
        pthread_t t;
        if (pthread_create(&t, NULL, mux_reader, NULL) != 0) fprintf(stderr, "mux: thread failed\n");
        else { pthread_detach(t); g_reader_started = 1; }
    }
}

/* --- FunctionFS descriptors --- */
static size_t put_iface(uint8_t *p, int num, int alt, int nendpoints) {
    p[0] = 9; p[1] = 0x04;
    p[2] = (uint8_t)num; p[3] = (uint8_t)alt;
    p[4] = (uint8_t)nendpoints;
    p[5] = USBMUX_CLASS; p[6] = USBMUX_SUBCLASS; p[7] = USBMUX_PROTOCOL;
    p[8] = 0;
    return 9;
}

static size_t put_ep(uint8_t *p, uint8_t addr, uint16_t maxpkt, uint8_t interval) {
    p[0] = 7; p[1] = 0x05;
    p[2] = addr;
    p[3] = 0x02;
    p[4] = (uint8_t)(maxpkt & 0xff);
    p[5] = (uint8_t)(maxpkt >> 8);
    p[6] = interval;
    return 7;
}

static int write_descriptors(int fd) {
    uint8_t buf[256];
    memset(buf, 0, sizeof buf);
    struct ffs_descs_head_v2 *h = (struct ffs_descs_head_v2 *)buf;
    uint8_t *p = buf + sizeof *h;
    uint32_t *counts = (uint32_t *)p;
    counts[0] = 3;                              /* interface + two endpoints */
    counts[1] = 3;
    p += 2 * sizeof(uint32_t);
    p += put_iface(p, 0, 0, 2);                 /* composite renumbers interfaces: start at 0 */
    p += put_ep(p, EP_OUT, 64, 0);
    p += put_ep(p, EP_IN, 64, 0);
    p += put_iface(p, 0, 0, 2);
    p += put_ep(p, EP_OUT, 512, 0);
    p += put_ep(p, EP_IN, 512, 0);
    h->magic = FFS_MAGIC_V2;
    h->length = (uint32_t)(p - buf);
    h->flags = FFS_FLAG_HAS_FS_DESC | FFS_FLAG_HAS_HS_DESC | FFS_FLAG_ALL_CTRL_RECIP;
    ssize_t n = write(fd, buf, h->length);
    if (n != (ssize_t)h->length) {
        fprintf(stderr, "ffs: descriptor write failed (%zd, errno=%d)\n", n, errno);
        return -1;
    }
    fprintf(stderr, "ffs: descriptors written (%u bytes, flags=0x%x)\n", h->length, h->flags);
    return 0;
}

/* After the descriptors the kernel sits in FFS_READ_STRINGS; only the strings chunk moves it to
 * FFS_ACTIVE, the state where ep0 events can be read at all.  Our descriptors reference no strings,
 * so the kernel short-circuits on needed_count == 0 and a zero-count chunk is valid. */
static int write_strings(int fd) {
    uint32_t chunk[4] = { 2u, 16u, 0u, 0u };
    ssize_t n = write(fd, chunk, sizeof chunk);
    if (n != (ssize_t)sizeof chunk) {
        fprintf(stderr, "ffs: strings write failed (%zd, errno=%d)\n", n, errno);
        return -1;
    }
    fprintf(stderr, "ffs: strings written\n");
    return 0;
}

#define CARPLAY_CONFIGURATION_REQUEST 0x52
/* An MFi head unit probes "CarPlay Supported Capabilities" before it will start CarPlay:
 * bmRequestType 0xC0, bRequest 0x53, wValue 0, wIndex 0, wLength 4 (libiPodForiAP2.so
 * iPod_get_usb_info, the production head unit logs the reply as "get CarPlay Supported Capabilities
 * ret[4][..][..]" and then sets carplaySupported = (reply[0] == 1)).  Answering the request
 * with the generic zeros below makes every MFi unit conclude "CarPlay support false" and
 * stop; the official okcar gadget answers 0x53 with the u32 value 1, which is what a real
 * iPhone reports here, so we do the same. */
#define CARPLAY_CAPABILITIES_REQUEST 0x53
/* Host-to-device vendor request the head unit sends as the last step of the wired handshake
 * (bmRequestType 0x40, bRequest 0x51, wValue 1).  What a real iPhone does next, measured on an
 * the production head unit unit with an iPhone 8 (2026-10-19): it becomes the USB HOST within milliseconds, the head
 * unit turns its own gadget into a device (idVendor 8087 / idProduct 0a68, functions iAP2.gs6 +
 * ncm.gs7) and the CarPlay transport (iAP2 + NCM) then runs on that link.  A phone that stays a
 * device leaves both ends waiting for a host, the head unit gives up after ~6 s and re-enumerates,
 * and the unit's CarPlay host reports "no network" on usbncm0 forever. */
#define CARPLAY_START_REQUEST 0x51

/* Overridable so the module can point this at whatever paths the running ROM exposes. */
static const char *gadget_dir(void) {
    const char *env = getenv("OKCAR_GADGET_DIR");
    return env && *env ? env : "/config/usb_gadget/g1";
}

static const char *usb_mode_path(void) {
    const char *env = getenv("OKCAR_USB_MODE");
    return env && *env ? env : "/sys/devices/platform/soc/a600000.ssusb/mode";
}

/* O_TRUNC is load-bearing, not tidiness.  The role-swap marker is rewritten in place as the swap
 * progresses ("pending\n" -> "1\n"), and without the truncate the shorter value only overwrites
 * the leading bytes of the longer one: writing "1\n" over "pending\n" leaves "1\nding\n" on disk.
 * The module's descriptor guard reads that file, so a garbled marker wedged the wired path
 * completely on 2026-10-19 -- the guard saw "the file exists" and stopped touching the port for
 * good.  Every caller here wants a whole-file replace, so truncate unconditionally. */
static int write_text(const char *path, const char *value) {
    int fd = open(path, O_WRONLY | O_CREAT | O_TRUNC, 0644);
    if (fd < 0) return -1;
    const size_t len = strlen(value);
    const ssize_t n = write(fd, value, len);
    close(fd);
    return n == (ssize_t)len ? 0 : -1;
}

static int read_text(const char *path, char *out, size_t out_len) {
    int fd = open(path, O_RDONLY);
    if (fd < 0) return -1;
    const ssize_t n = read(fd, out, out_len - 1);
    close(fd);
    if (n <= 0) return -1;
    out[n] = '\0';
    char *nl = strchr(out, '\n');
    if (nl) *nl = '\0';
    return 0;
}

/* Set when the head unit sends 0x51; the role swap then waits for the unit to drop the link.
 * Measured on the production head unit (2026-10-19 00:40): after 0x51 it closes the session itself
 * ("iPod_stop_carplay_server"), reopens our device node (iPod_get_usb_info -> /dev/bus/usb/...),
 * and only then flips its own port to device.  Swapping at 0x51 kills that reopen (ENODEV) and the
 * unit aborts, so the swap belongs on the FFS_DISABLE that follows. */
static volatile int g_start_request_ms = 0;

static int now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int)(ts.tv_sec * 1000 + ts.tv_nsec / 1000000);
}

static void arm_role_swap(void) {
    /* Field switch: OHLY leave the port alone (OKCAR_NO_ROLE_SWAP=1) so we can watch what the head
     * unit does on its own after 0x51 -- that is how the role-swap trigger gets identified. */
    const char *off = getenv("OKCAR_NO_ROLE_SWAP");
    if (off && *off && *off != '0') {
        fprintf(stderr, "ffs: role swap skipped (OKCAR_NO_ROLE_SWAP) -- watching the head unit\n");
        return;
    }
    char udc[160];
    char mode[64] = "";
    snprintf(udc, sizeof udc, "%s/UDC", gadget_dir());
    /* dwc3 refuses the mode write while our gadget owns the UDC, so let go of it first.  The two
     * writes are back to back on purpose: the head unit starts its own role swap on this same
     * event and gives up if the bus stays dead (measured: a 1.5 s gap was already too slow). */
    /* The marker goes down *before* the unbind: the module's guard re-binds any gadget it finds
     * unbound, and those two writes are only microseconds apart.  "pending" already counts as
     * "do not touch the port"; the success path rewrites it with the value the status reads. */
    write_text("/data/local/tmp/okcar-usb-host", "pending\n");
    if (write_text(udc, "") != 0) {
        fprintf(stderr, "ffs: role swap: cannot unbind %s (errno=%d)\n", udc, errno);
    }
    for (int i = 0; i < 60; i++) {
        write_text(usb_mode_path(), "host");
        if (read_text(usb_mode_path(), mode, sizeof mode) == 0 && strcmp(mode, "host") == 0) {
            fprintf(stderr, "ffs: usb role -> host after %d ms -- the head unit is the device now\n",
                    (i + 1) * 50);
            write_text("/data/local/tmp/okcar-usb-host", "1\n");
            return;
        }
        usleep(50000);
    }
    fprintf(stderr, "ffs: role swap FAILED (mode=%s)\n", mode);
    unlink("/data/local/tmp/okcar-usb-host");
}

static void handle_setup(int fd, const struct usb_ctrlrequest *r) {
    const int direction_in = (r->bRequestType & 0x80) != 0;
    fprintf(stderr, "ffs: setup %02x %02x wv=%04x wi=%04x wl=%u\n",
            r->bRequestType, r->bRequest, r->wValue, r->wIndex, r->wLength);
    if (direction_in) {
        static uint8_t zeros[256];
        size_t want = r->wLength > sizeof zeros ? sizeof zeros : r->wLength;
        if (r->bRequest == CARPLAY_CONFIGURATION_REQUEST) {
            uint8_t answer = 0;
            if (write(fd, &answer, 1) != 1) fprintf(stderr, "ffs: answer write failed %d\n", errno);
            else fprintf(stderr, "ffs: answered CarPlay configuration request\n");
            return;
        }
        if (r->bRequest == CARPLAY_CAPABILITIES_REQUEST) {
            static const uint8_t supported[4] = { 1, 0, 0, 0 };   /* u32 1 = CarPlay capable */
            size_t want = r->wLength < sizeof supported ? r->wLength : sizeof supported;
            if (write(fd, supported, want) != (ssize_t)want) {
                fprintf(stderr, "ffs: capabilities answer failed %d\n", errno);
            } else {
                fprintf(stderr, "ffs: answered CarPlay capabilities (%zu bytes, supported)\n", want);
            }
            return;
        }
        if (want == 0) return;
        if (write(fd, zeros, want) != (ssize_t)want) fprintf(stderr, "ffs: IN write failed %d\n", errno);
    } else {
        if (r->wLength) {
            uint8_t sink[256];
            size_t want = r->wLength > sizeof sink ? sizeof sink : r->wLength;
            if (read(fd, sink, want) < 0) fprintf(stderr, "ffs: OUT read failed %d\n", errno);
        }
        /* 0x51 arrives with wLength 0 (the production head unit sends 40 51 0001 0000 0000), so this must
         * not hide inside the "has a data stage" branch: the whole hand-off hangs on it. */
        if (r->bRequest == CARPLAY_START_REQUEST) {
            g_start_request_ms = now_ms();
            fprintf(stderr, "ffs: 0x51 seen -- waiting for the head unit to drop the link\n");
        }
    }
}

int main(int argc, char **argv) {
    if (argc > 1 && strcmp(argv[1], "--gen-identity") == 0) {
        int g = gen_identity(argc > 2 && strcmp(argv[2], "--force") == 0);
        return g < 0 ? 1 : 0;          /* 0: generated, or a pair was already installed */
    }
    const char *ep0 = argc > 1 ? argv[1] : "/dev/usb-ffs/okcar/ep0";
    int fd = open(ep0, O_RDWR);
    if (fd < 0) {
        fprintf(stderr, "ffs: cannot open %s (errno=%d)\n", ep0, errno);
        return 1;
    }
    if (write_descriptors(fd) != 0) return 1;
    if (write_strings(fd) != 0) return 1;

    for (;;) {
        struct ffs_event ev;
        ssize_t n = read(fd, &ev, sizeof ev);
        if (n < 0) {
            if (errno == EINTR) continue;
            fprintf(stderr, "ffs: event read failed (errno=%d)\n", errno);
            return 1;
        }
        if (n < (ssize_t)sizeof ev) continue;
        switch (ev.type) {
        case FFS_BIND:    fprintf(stderr, "ffs: bind\n"); break;
        case FFS_UNBIND:  fprintf(stderr, "ffs: unbind\n"); break;
        case FFS_ENABLE:  fprintf(stderr, "ffs: enabled\n"); mux_start(); break;
        case FFS_DISABLE:
            fprintf(stderr, "ffs: disabled\n");
            mux_stop();
            if (g_start_request_ms && now_ms() - g_start_request_ms < 4000) {
                g_start_request_ms = 0;
                fprintf(stderr, "ffs: link dropped right after 0x51 -- offering ourselves as host\n");
                arm_role_swap();
            }
            break;
        case FFS_SETUP:   handle_setup(fd, &ev.setup); break;
        default: break;
        }
    }
}
