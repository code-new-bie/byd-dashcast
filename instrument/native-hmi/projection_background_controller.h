#ifndef DASHCAST_PROJECTION_BACKGROUND_CONTROLLER_H
#define DASHCAST_PROJECTION_BACKGROUND_CONTROLLER_H

#include <stdbool.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* Internal, SDK-independent controller API. NOT a DCV1 or IPC wire format. */
#define DC_BG_PREPARE_MS UINT64_C(500)
#define DC_BG_LEASE_MS UINT64_C(1000)
#define DC_BG_OUTPUT_MAX_AGE_MS UINT64_C(250)
#define DC_BG_READY_BINDINGS UINT32_C(1)
#define DC_BG_READY_FOREGROUND UINT32_C(2)
#define DC_BG_READY_ALPHA UINT32_C(4)
#define DC_BG_READY_COMPOSITION UINT32_C(8)
#define DC_BG_READY_PAGE UINT32_C(16)
#define DC_BG_READY_ALL UINT32_C(31)

typedef enum {
    DC_BG_NATIVE, DC_BG_PREPARING, DC_BG_ACTIVE, DC_BG_RESTORING, DC_BG_BLOCKED
} DcBgState;

typedef enum {
    DC_BG_OK, DC_BG_INVALID, DC_BG_WRONG_THREAD, DC_BG_BUSY, DC_BG_STALE_SESSION,
    DC_BG_NOT_READY, DC_BG_EXPIRED, DC_BG_NO_OUTPUT, DC_BG_APPLY_FAILED,
    DC_BG_RESTORE_FAILED, DC_BG_CLOCK_ERROR, DC_BG_GENERATION_EXHAUSTED
} DcBgResult;

typedef struct {
    uint64_t hmiEpoch;
    uint64_t generation;
    uint32_t receiverPid;
} DcBgSession;

typedef struct {
    uint64_t sceneEpoch;
    uint32_t verified;
} DcBgReadiness;

typedef struct {
    /* Actual output/post evidence, not FRAME ACK/submission count/heartbeat. */
    uint64_t serial;
    uint64_t monotonicMs;
} DcBgOutput;

typedef struct {
    bool (*onMainThread)(void *context);
    bool (*inspect)(void *context, DcBgReadiness *readiness);
    bool (*observeOutput)(void *context, DcBgSession session, DcBgOutput *output);
    /* Only decoration draws. Never whole-window alpha or foreground opacity. */
    bool (*suppressBackground)(void *context, uint64_t sceneEpoch);
    /* Resolve the CURRENT scene; restore latest normal state, not a snapshot. */
    bool (*restoreBackground)(void *context);
    /* Only this session's video, or confirm that it no longer exists. */
    bool (*withdrawVideo)(void *context, DcBgSession session);
} DcBgPort;

typedef struct {
    DcBgState state;
    DcBgSession session;
    uint64_t sceneEpoch;
    uint64_t nextGeneration;
    uint64_t lastNow;
    uint64_t deadline;
    uint64_t outputSerial;
    uint64_t outputTime;
    uint64_t renewedSerial;
    DcBgPort port;
    void *context;
    bool initialized;
    bool timeSeen;
    bool suppressed;
    bool inOperation;
} DcBgController;

/* New nonzero epoch per HMI instance; main-thread-only, serialized operations. */
DcBgResult dc_bg_init(DcBgController *controller, const DcBgPort *port,
                     void *context, uint64_t hmiEpoch);
DcBgResult dc_bg_prepare(DcBgController *controller, uint32_t trustedReceiverPid,
                        uint64_t localMonotonicMs, DcBgSession *session);
DcBgResult dc_bg_commit(DcBgController *controller, DcBgSession session, uint64_t localMonotonicMs);
DcBgResult dc_bg_renew(DcBgController *controller, DcBgSession session, uint64_t localMonotonicMs);
DcBgResult dc_bg_restore(DcBgController *controller, DcBgSession session, uint64_t localMonotonicMs);
/* Call every HMI frame, INCLUDING when there is no new control request. */
DcBgResult dc_bg_tick(DcBgController *controller, uint64_t localMonotonicMs);
/* Explicit owner recovery only; never automatically recover and restart video. */
DcBgResult dc_bg_recover(DcBgController *controller, DcBgSession session, uint64_t localMonotonicMs);

#ifdef __cplusplus
}
#endif
#endif
